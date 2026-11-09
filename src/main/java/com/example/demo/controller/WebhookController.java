package com.example.demo.controller;

import com.example.demo.dto.SignalResponse;
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
    public ResponseEntity<SignalResponse> receiveSignal(
            @RequestHeader(value = "X-Webhook-Secret", required = false) String incomingSecret,
            @RequestBody TradingSignalRequest request) {

        String signalId = request != null ? request.signalId() : null;

        // 1. GÜVENLİK KONTROLÜ
        if (incomingSecret == null || !webhookSecret.equals(incomingSecret)) {
            log.warn("KORSAN SALDIRI ENGELLENDİ! Yanlış veya eksik şifre denemesi.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new SignalResponse(signalId, "ERROR", "Hata: Yetkisiz Erişim!"));
        }

        // 2. İŞLEM VE HATA YAKALAMA
        try {
            log.debug("Webhook isteği güvenle karşılandı: Symbol={}, Action={}", request.symbol(), request.action());

            String status = signalProcessingService.processSignal(request);

            if ("FILLED".equalsIgnoreCase(status)) {
                return ResponseEntity.ok(new SignalResponse(signalId, status, "Sinyal başarıyla işlendi ve Binance emri gerçekleşti."));
            }

            if ("NEW".equalsIgnoreCase(status)) {
                return ResponseEntity.ok(new SignalResponse(signalId, status, "Emir borsaya iletildi, eşleşme bekleniyor"));
            }

            if ("PARTIALLY_FILLED".equalsIgnoreCase(status)) {
                return ResponseEntity.ok(new SignalResponse(signalId, status, "Emir kısmen gerçekleşti"));
            }

            if ("UNKNOWN".equalsIgnoreCase(status)) {
                log.warn("Emir durumu belirsiz (UNKNOWN), 202 Accepted dönülüyor. SignalId={}", signalId);
                return ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(new SignalResponse(signalId, "UNKNOWN", "Emir Binance'e iletildi ancak durumu henüz belirsiz (UNKNOWN)."));
            }

            return ResponseEntity.ok(new SignalResponse(signalId, status, "İşlem tamamlandı. Durum: " + status));

        } catch (Exception e) {
            String errorMsg = e.getMessage();

            if (errorMsg != null && errorMsg.contains("IDEMPOTENCY_ERROR")) {
                log.warn("Tekrarlanan istek reddedildi: {}", errorMsg);
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new SignalResponse(signalId, "ERROR", errorMsg));
            } else if (errorMsg != null && (errorMsg.contains("ORDER_NOT_FOUND") || errorMsg.contains("BELİRSİZ") || errorMsg.contains("TIMEOUT_UNKNOWN_STATE") || errorMsg.contains("belirsiz"))) {
                log.warn("Emir durumu belirsiz (202 Accepted): {}", errorMsg);
                return ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(new SignalResponse(signalId, "UNKNOWN", errorMsg));
            } else if (e instanceof IllegalArgumentException || (errorMsg != null && (errorMsg.contains("VALIDATION_ERROR") || errorMsg.contains("BINANCE_REJECTED")))) {
                log.error("İstek hatası: {}", errorMsg);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(new SignalResponse(signalId, "ERROR", errorMsg));
            } else {
                log.error("Beklenmedik sistem hatası: {}", errorMsg);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(new SignalResponse(signalId, "ERROR", errorMsg));
            }
        }
    }

    @GetMapping(value = "/status/{signalId}", produces = "application/json")
    public ResponseEntity<SignalResponse> requeryStatus(
            @RequestHeader(value = "X-Webhook-Secret", required = false) String incomingSecret,
            @PathVariable String signalId) {

        if (incomingSecret == null || !webhookSecret.equals(incomingSecret)) {
            log.warn("KORSAN SALDIRI ENGELLENDİ! Yanlış veya eksik şifre denemesi.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new SignalResponse(signalId, "ERROR", "Hata: Yetkisiz Erişim!"));
        }

        try {
            SignalResponse response = signalProcessingService.requeryOrderStatus(signalId);
            if ("UNKNOWN".equalsIgnoreCase(response.status())) {
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
            }
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new SignalResponse(signalId, "ERROR", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new SignalResponse(signalId, "ERROR", e.getMessage()));
        }
    }
}
