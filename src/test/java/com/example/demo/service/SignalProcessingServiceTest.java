package com.example.demo.service;

import com.binance.connector.client.exceptions.BinanceConnectorException;
import com.binance.connector.client.impl.SpotClientImpl;
import com.example.demo.dto.TradingSignalRequest;
import com.example.demo.repository.SignalRepository;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest
public class SignalProcessingServiceTest {

    @Autowired
    private SignalProcessingService signalProcessingService;

    @Autowired
    private SignalRepository signalRepository;

    private static volatile Object activeMockConstructionMap = null;

    private static void captureMockConstruction() {
        try {
            Object mockMaker = org.mockito.internal.configuration.plugins.Plugins.getMockMaker();
            Field delegateField = mockMaker.getClass().getDeclaredField("inlineDelegateByteBuddyMockMaker");
            delegateField.setAccessible(true);
            Object delegate = delegateField.get(mockMaker);
            Field mcField = delegate.getClass().getDeclaredField("mockedConstruction");
            mcField.setAccessible(true);
            org.mockito.internal.util.concurrent.DetachedThreadLocal<?> dtl =
                    (org.mockito.internal.util.concurrent.DetachedThreadLocal<?>) mcField.get(delegate);
            activeMockConstructionMap = dtl.get();
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static void inheritMockConstruction() {
        try {
            if (activeMockConstructionMap != null) {
                Object mockMaker = org.mockito.internal.configuration.plugins.Plugins.getMockMaker();
                Field delegateField = mockMaker.getClass().getDeclaredField("inlineDelegateByteBuddyMockMaker");
                delegateField.setAccessible(true);
                Object delegate = delegateField.get(mockMaker);
                Field mcField = delegate.getClass().getDeclaredField("mockedConstruction");
                mcField.setAccessible(true);
                org.mockito.internal.util.concurrent.DetachedThreadLocal<Object> dtl =
                        (org.mockito.internal.util.concurrent.DetachedThreadLocal<Object>) mcField.get(delegate);
                dtl.set(activeMockConstructionMap);
            }
        } catch (Exception ignored) {
        }
    }

    @Test
    public void testConcurrentIdenticalSignals_OnlyOneProceeds() throws InterruptedException {
        String signalId = UUID.randomUUID().toString();
        TradingSignalRequest request = new TradingSignalRequest("BTCUSDT", "BUY", null, null, signalId, new BigDecimal("1"));

        int threadCount = 5;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // Binance Client'i taklit et (Mock)
        try (MockedConstruction<SpotClientImpl> mocked = Mockito.mockConstruction(
                SpotClientImpl.class,
                Mockito.withSettings().defaultAnswer(Mockito.RETURNS_DEEP_STUBS),
                (mock, context) -> {
                    Mockito.when(mock.createTrade().newOrder(any())).thenReturn("{\"status\":\"FILLED\"}");
                })) {
            captureMockConstruction();
            for (int i = 0; i < threadCount; i++) {
                executorService.submit(() -> {
                    inheritMockConstruction();
                    try {
                        latch.await(); // Tüm thread'leri aynı anda başlatmak için beklet
                        signalProcessingService.processSignal(request);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        if (e.getMessage() != null && e.getMessage().contains("IDEMPOTENCY_ERROR")) {
                            failCount.incrementAndGet();
                        }
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            latch.countDown(); // Ateşle!
            doneLatch.await(); // Tüm thread'lerin bitmesini bekle

            assertEquals(1, successCount.get(), "Sadece 1 işlem Binance'e gitmeli");
            assertEquals(threadCount - 1, failCount.get(), "Diğer tüm eşzamanlı işlemler veritabanı kısıtlamasına takılmalı");
            Mockito.verify(mocked.constructed().get(0).createTrade(), Mockito.times(1)).newOrder(Mockito.any());
        }
    }

    @Test
    public void testUnknownRecordPersistsAfterException() {
        String signalId = UUID.randomUUID().toString();
        TradingSignalRequest request = new TradingSignalRequest("BTCUSDT", "BUY", null, null, signalId, new BigDecimal("1"));

        try (MockedConstruction<SpotClientImpl> mocked = Mockito.mockConstruction(
                SpotClientImpl.class,
                Mockito.withSettings().defaultAnswer(Mockito.RETURNS_DEEP_STUBS),
                (mock, context) -> {
                    // Binance'in ağ hatası (BinanceConnectorException) vermesini simüle et
                    Mockito.when(mock.createTrade().newOrder(Mockito.any()))
                            .thenThrow(new BinanceConnectorException("Simulated Network Timeout"));
                    Mockito.when(mock.createTrade().getOrder(Mockito.any()))
                            .thenThrow(new BinanceConnectorException("Simulated Query Timeout"));
                })) {
            assertThrows(RuntimeException.class, () -> {
                signalProcessingService.processSignal(request);
            });

            // Hata fırlatılmasına rağmen (Rollback olmadığı için) kayıt veritabanında UNKNOWN olarak kalmalı
            assertTrue(signalRepository.existsById(signalId), "Kayıt veritabanından silinmemeli");
            assertEquals("UNKNOWN", signalRepository.findById(signalId).get().getOrderStatus(), "Kayıt UNKNOWN olarak kalmalı");
        }
    }

    @Test
    public void testSequentialDuplicateSignals_AreRejected() {
        String signalId = UUID.randomUUID().toString();
        TradingSignalRequest request = new TradingSignalRequest("BTCUSDT", "BUY", null, null, signalId, new BigDecimal("1"));

        try (MockedConstruction<SpotClientImpl> mocked = Mockito.mockConstruction(SpotClientImpl.class, 
                Mockito.withSettings().defaultAnswer(Mockito.RETURNS_DEEP_STUBS),
                (mock, context) -> {
                    Mockito.when(mock.createTrade().newOrder(Mockito.any())).thenReturn("{\"status\":\"FILLED\"}");
                })) {
            
            // İlk istek başarıyla işlenmeli
            assertDoesNotThrow(() -> signalProcessingService.processSignal(request));
            
            // Aynı UUID ile gelen İkinci istek anında reddedilmeli
            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                signalProcessingService.processSignal(request);
            });
            assertTrue(exception.getMessage().contains("IDEMPOTENCY_ERROR"));
            
            // Binance'e toplamda SADECE 1 KERE gidildiğini fiziksel olarak doğrula
            Mockito.verify(mocked.constructed().get(0).createTrade(), Mockito.times(1)).newOrder(Mockito.any());
        }
    }
}
