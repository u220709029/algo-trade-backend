package com.example.demo.controller;

import com.example.demo.dto.TradingSignalRequest;
import com.example.demo.service.SignalProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private final SignalProcessingService signalProcessingService;

    // 1. Şifreyi properties dosyasından kasaya çekiyoruz
    @Value("${webhook.secret}")
    private String webhookSecret;

    @PostMapping(value = "/api/webhook", consumes = "application/json", produces = "application/json")
    public ResponseEntity<String> receiveSignal(
            @RequestHeader(value = "X-Webhook-Secret", required = false) String incomingSecret,
            @RequestBody TradingSignalRequest request) {

        // 1. GÜVENLİK KONTROLÜ
        if (incomingSecret == null || !webhookSecret.equals(incomingSecret)) {
            log.warn("KORSAN SALDIRI ENGELLENDİ! Yanlış veya eksik şifre denemesi.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Hata: Yetkisiz Erişim!");
        }

        // 2. İŞLEM VE HATA YAKALAMA (YENİ EKLENEN KISIM)
        try {
            log.debug("Webhook isteği güvenle karşılandı: Symbol={}, Action={}", request.symbol(), request.action());

            // Service katmanını çağırıyoruz. Eğer Binance reddederse buradan hata fırlayacak.
            signalProcessingService.processSignal(request);

            // Hata fırlamazsa alt satıra geçer ve başarılı cevabı döner
            return ResponseEntity.ok("Sinyal başarıyla işlendi ve Binance emri gerçekleşti.");

        } catch (Exception e) {
            // Service'den fırlatılan hatayı burada yakalayıp müşteriye (400 Bad Request) olarak dönüyoruz
            log.error("İşlem başarısız oldu, müşteriye hata dönülüyor: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("İşlem Başarısız: " + e.getMessage());
        }
    }
}