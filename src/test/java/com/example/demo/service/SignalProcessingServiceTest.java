package com.example.demo.service;

import com.binance.connector.client.impl.SpotClientImpl;
import com.example.demo.dto.TradingSignalRequest;
import com.example.demo.repository.SignalRepository;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

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
        var unexpectedErrors =
                new java.util.concurrent.ConcurrentLinkedQueue<Exception>();

        try {
            for (int i = 0; i < threadCount; i++) {
                executorService.submit(() -> {
                    try (MockedConstruction<SpotClientImpl> mocked = Mockito.mockConstruction(
                            SpotClientImpl.class,
                            Mockito.withSettings().defaultAnswer(Mockito.RETURNS_DEEP_STUBS),
                            (mock, context) -> Mockito.when(mock.createTrade().newOrder(any()))
                                    .thenReturn("{\"status\":\"FILLED\"}"))) {

                        latch.await();
                        signalProcessingService.processSignal(request);
                        successCount.incrementAndGet();

                    } catch (Exception e) {
                        if (e.getMessage() != null && e.getMessage().contains("IDEMPOTENCY_ERROR")) {
                            failCount.incrementAndGet();
                        } else {
                            unexpectedErrors.add(e);
                        }
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            latch.countDown();
            if (!doneLatch.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError("Worker thread'ler 15 saniyede bitmedi");
            }

            if (!unexpectedErrors.isEmpty()) {
                throw new AssertionError("Worker thread'de beklenmeyen hata", unexpectedErrors.peek());
            }

            assertEquals(1, successCount.get(), "Yalnızca bir sinyal başarıyla işlenmeli");
            assertEquals(threadCount - 1, failCount.get(), "Diğer sinyaller yinelenen istek olarak reddedilmeli");
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    public void testUnknownRecordPersistsAfterException() {
        String signalId = UUID.randomUUID().toString();
        TradingSignalRequest request = new TradingSignalRequest("BTCUSDT", "BUY", null, null, signalId, new BigDecimal("1"));

        try (MockedConstruction<SpotClientImpl> mocked = Mockito.mockConstruction(SpotClientImpl.class, (mock, context) -> {
            // Binance'in zaman aşımı/hata vermesini simüle et
            Mockito.when(mock.createTrade()).thenThrow(new RuntimeException("Simulated Network Error"));
        })) {
            assertThrows(RuntimeException.class, () -> {
                signalProcessingService.processSignal(request);
            });

            // Hata fırlatılmasına rağmen (Rollback olmadığı için) kayıt veritabanında SYSTEM_ERROR olarak kalmalı
            assertTrue(signalRepository.existsById(signalId), "Kayıt veritabanından silinmemeli");
            assertEquals("SYSTEM_ERROR", signalRepository.findById(signalId).get().getOrderStatus(), "Kayıt SYSTEM_ERROR olarak kalmalı");
        }
    }
}
