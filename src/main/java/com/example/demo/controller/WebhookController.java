package com.example.demo.controller;

import com.example.demo.dto.TradingSignalRequest;
import com.example.demo.service.SignalProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TradingView Webhook sinyallerini karşılayan REST API Controller katmanı.
 * Clean Architecture prensipleri gereği bu katmanda hiçbir iş mantığı (business logic) bulunmaz;
 * yalnızca gelen isteği doğrular ve ilgili servis katmanına delege eder.
 */
@Slf4j
@RestController
@RequestMapping("/api/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final SignalProcessingService signalProcessingService;

    /**
     * TradingView veya diğer dış kaynaklardan gelen POST sinyal isteklerini karşılar.
     *
     * @param request JSON gövdesinden deserialize edilen TradingSignalRequest record nesnesi
     * @return 200 OK yanıtı
     */
    @PostMapping
    public ResponseEntity<String> receiveSignal(@RequestBody TradingSignalRequest request) {
        log.debug("Webhook isteği karşılandı: Symbol={}, Action={}", request.symbol(), request.action());

        // İş mantığı tamamen servis katmanına devrediliyor
        signalProcessingService.processSignal(request);

        return ResponseEntity.ok("Sinyal başarıyla alındı ve işleme kuyruğuna iletildi.");
    }
}
