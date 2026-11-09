package com.example.demo.service;

import com.binance.connector.client.exceptions.BinanceConnectorException;
import com.binance.connector.client.impl.SpotClientImpl;
import com.example.demo.dto.TradingSignalRequest;
import com.example.demo.entity.SignalRecord;
import com.example.demo.repository.SignalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest
@ActiveProfiles("test")
public class SignalProcessingServiceTest {

    @Autowired
    private SignalProcessingService signalProcessingService;

    @Autowired
    private SignalRepository signalRepository;

    @MockitoBean(answers = Answers.RETURNS_DEEP_STUBS)
    private SpotClientImpl binanceClient;

    @BeforeEach
    public void setUp() {
        Mockito.reset(binanceClient);
    }

    @Test
    public void testConcurrentIdenticalSignals_OnlyOneProceeds() throws InterruptedException {
        String signalId = UUID.randomUUID().toString();
        TradingSignalRequest request = new TradingSignalRequest("BTCUSDT", "BUY", null, null, signalId, new BigDecimal("1"));

        Mockito.when(binanceClient.createTrade().newOrder(any())).thenReturn("{\"status\":\"FILLED\"}");

        int threadCount = 5;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
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
        Mockito.verify(binanceClient.createTrade(), Mockito.times(1)).newOrder(any());
    }

    @Test
    public void testUnknownRecordPersistsAfterException() {
        String signalId = UUID.randomUUID().toString();
        TradingSignalRequest request = new TradingSignalRequest("BTCUSDT", "BUY", null, null, signalId, new BigDecimal("1"));

        // Binance'in ağ hatası (BinanceConnectorException) vermesini simüle et
        Mockito.when(binanceClient.createTrade().newOrder(any()))
                .thenThrow(new BinanceConnectorException("Simulated Network Timeout"));
        Mockito.when(binanceClient.createTrade().getOrder(any()))
                .thenThrow(new BinanceConnectorException("Simulated Query Timeout"));

        assertThrows(RuntimeException.class, () -> {
            signalProcessingService.processSignal(request);
        });

        // Hata fırlatılmasına rağmen (Rollback olmadığı için) kayıt veritabanında UNKNOWN olarak kalmalı
        assertTrue(signalRepository.existsById(signalId), "Kayıt veritabanından silinmemeli");
        assertEquals("UNKNOWN", signalRepository.findById(signalId).get().getOrderStatus(), "Kayıt UNKNOWN olarak kalmalı");
    }

    @Test
    public void testSequentialDuplicateSignals_AreRejected() {
        String signalId = UUID.randomUUID().toString();
        TradingSignalRequest request = new TradingSignalRequest("BTCUSDT", "BUY", null, null, signalId, new BigDecimal("1"));

        Mockito.when(binanceClient.createTrade().newOrder(any())).thenReturn("{\"status\":\"FILLED\"}");

        // İlk istek başarıyla işlenmeli
        assertDoesNotThrow(() -> signalProcessingService.processSignal(request));

        // Aynı UUID ile gelen İkinci istek anında reddedilmeli
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            signalProcessingService.processSignal(request);
        });
        assertTrue(exception.getMessage().contains("IDEMPOTENCY_ERROR"));

        // Binance'e toplamda SADECE 1 KERE gidildiğini fiziksel olarak doğrula
        Mockito.verify(binanceClient.createTrade(), Mockito.times(1)).newOrder(any());
    }

    @Test
    public void testRequery_UnknownToFilled() {
        String signalId = UUID.randomUUID().toString();
        SignalRecord record = new SignalRecord(signalId, "BTCUSDT", "BUY", "UNKNOWN", System.currentTimeMillis());
        signalRepository.saveAndFlush(record);

        Mockito.when(binanceClient.createTrade().getOrder(any())).thenReturn("{\"status\":\"FILLED\"}");

        String status = signalProcessingService.requeryOrderStatus(signalId);

        assertEquals("FILLED", status, "Dönen durum FILLED olmalı");
        assertEquals("FILLED", signalRepository.findById(signalId).get().getOrderStatus(), "Veritabanındaki kayıt FILLED olarak güncellenmeli");
    }

    @Test
    public void testRequery_OrderNotFound_ReturnsUnknown() {
        String signalId = UUID.randomUUID().toString();
        SignalRecord record = new SignalRecord(signalId, "BTCUSDT", "BUY", "UNKNOWN", System.currentTimeMillis());
        signalRepository.saveAndFlush(record);

        Mockito.when(binanceClient.createTrade().getOrder(any()))
                .thenThrow(new com.binance.connector.client.exceptions.BinanceClientException("Order does not exist", "Order does not exist", 400, -2013));

        String status = assertDoesNotThrow(() -> signalProcessingService.requeryOrderStatus(signalId));

        assertEquals("UNKNOWN", status, "Metot Exception fırlatmadan UNKNOWN dönmeli");
        assertEquals("UNKNOWN", signalRepository.findById(signalId).get().getOrderStatus(), "Veritabanındaki kayıt UNKNOWN olarak kalmalı");
    }
}
