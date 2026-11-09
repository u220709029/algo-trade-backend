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
            String status = signalProcessingService.processSignal(request);

            if ("UNKNOWN".equalsIgnoreCase(status)) {
                log.warn("Emir durumu belirsiz (UNKNOWN), 202 Accepted dönülüyor. SignalId={}", request.signalId());
                return ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body("Emir Binance'e iletildi ancak durumu henüz belirsiz (UNKNOWN).");
            }

            // Hata fırlamazsa alt satıra geçer ve başarılı cevabı döner
            return ResponseEntity.ok("Sinyal başarıyla işlendi ve Binance emri gerçekleşti. Durum: " + status);

        } catch (Exception e) {
            String errorMsg = e.getMessage();

            // Eğer hata IDEMPOTENCY (Çift İstek) ise 409 Conflict dön
            if (errorMsg != null && errorMsg.contains("IDEMPOTENCY_ERROR")) {
                log.warn("Tekrarlanan istek reddedildi: {}", errorMsg);
                return ResponseEntity.status(HttpStatus.CONFLICT).body(errorMsg);
            }

            // Eğer hata ZAMAN AŞIMI ise 504 Gateway Timeout dön
            if (errorMsg != null && errorMsg.contains("TIMEOUT_UNKNOWN_STATE")) {
                log.error("KRİTİK UYARI: İşlem durumu belirsiz! {}", errorMsg);
                return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body("Durum Belirsiz: " + errorMsg);
            }

            // Diğer normal Binance redleri için 400 Bad Request dön
            log.error("İşlem başarısız oldu: {}", errorMsg);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("İşlem Başarısız: " + errorMsg);
        }
    }
}