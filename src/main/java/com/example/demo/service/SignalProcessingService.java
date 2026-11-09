package com.example.demo.service;

import com.binance.connector.client.exceptions.BinanceClientException;
import com.binance.connector.client.exceptions.BinanceConnectorException;
import com.binance.connector.client.impl.SpotClientImpl;
import com.example.demo.dto.TradingSignalRequest;
import com.example.demo.entity.SignalRecord;
import com.example.demo.repository.SignalRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SignalProcessingService {

    private final SpotClientImpl binanceClient;
    private final SignalRepository signalRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String processSignal(TradingSignalRequest signal) {
        
        if (signal.signalId() == null) {
            throw new IllegalArgumentException("VALIDATION_ERROR: signalId eksik!");
        }
        try {
            UUID.fromString(signal.signalId());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("VALIDATION_ERROR: signalId geçerli bir UUID formatında olmalıdır.");
        }
        
        if (signal.action() == null || (!signal.action().equalsIgnoreCase("BUY") && !signal.action().equalsIgnoreCase("SELL"))) {
            throw new IllegalArgumentException("VALIDATION_ERROR: İşlem yönü sadece BUY veya SELL olabilir.");
        }

        if (signal.quantity() == null) {
            throw new IllegalArgumentException("VALIDATION_ERROR: Miktar (quantity) gönderilmek zorundadır.");
        }
        if (signal.quantity().compareTo(BigDecimal.ZERO) <= 0 || signal.quantity().compareTo(new BigDecimal("10")) > 0) {
            throw new IllegalArgumentException("VALIDATION_ERROR: Miktar 0'dan büyük ve 10'dan küçük olmalıdır.");
        }

        // --- 2. KALICI VERİTABANINDA ÇİFT İŞLEM KORUMASI (IDEMPOTENCY) ---
        String signalId = signal.signalId();
        try {
            signalRepository.insertSignalStrict(signalId, signal.symbol(), signal.action(), "UNKNOWN", System.currentTimeMillis());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.warn("🚨 ÇİFT İŞLEM ENGELLENDİ! '{}' ID'li sinyal veritabanına yazılırken çakışma yaşandı.", signalId);
            throw new RuntimeException("IDEMPOTENCY_ERROR: Bu sinyal zaten işlendi!");
        }

        // --- 3. BORSAYA İLETİM ---
        return sendMarketOrder(signal, signalId);
    }

    private String sendMarketOrder(TradingSignalRequest signal, String signalId) {
        try {
            LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("symbol", signal.symbol());
            parameters.put("side", signal.action());
            parameters.put("type", "MARKET");
            parameters.put("quantity", signal.quantity());
            parameters.put("newClientOrderId", signalId);
            parameters.put("recvWindow", 60000L);

            String response = binanceClient.createTrade().newOrder(parameters);
            
            JsonNode root = objectMapper.readTree(response);
            String status = root.has("status") ? root.path("status").asText() : "UNKNOWN";
            
            log.info("✅ Binance Emri Başarıyla Gerçekleşti! Durum: {}", status);
            updateRecordStatus(signalId, status);
            return status;

        } catch (BinanceClientException e) {
            if (e.getErrorCode() == -1007) {
                return checkOrderStatus(signal, signalId);
            } else {
                updateRecordStatus(signalId, "REJECTED_" + e.getErrorCode());
                throw new RuntimeException("BINANCE_REJECTED: " + e.getMessage());
            }

        } catch (BinanceConnectorException e) {
            return checkOrderStatus(signal, signalId);
            
        } catch (Exception e) {
            updateRecordStatus(signalId, "SYSTEM_ERROR");
            throw new RuntimeException("SYSTEM_ERROR: " + e.getMessage());
        }
    }

    private String checkOrderStatus(TradingSignalRequest signal, String signalId) {
        log.warn("🚨 DURUM BELİRSİZ (-1007 veya Ağ Hatası). Binance'e '{}' ID'si ile akıllı sorgu atılıyor...", signalId);
        
        try {
            LinkedHashMap<String, Object> queryParams = new LinkedHashMap<>();
            queryParams.put("symbol", signal.symbol());
            queryParams.put("origClientOrderId", signalId);
            queryParams.put("recvWindow", 60000L);
            
            String response = binanceClient.createTrade().getOrder(queryParams);
            
            JsonNode root = objectMapper.readTree(response);
            String status = root.has("status") ? root.path("status").asText() : "UNKNOWN";
            
            log.info("✅ SORGULAMA BAŞARILI: Emir Binance'e ulaşmış. Gerçek Durumu: {}", status);
            updateRecordStatus(signalId, status);
            return status;
            
        } catch (BinanceClientException queryEx) {
            int errCode = queryEx.getErrorCode();
            
            if (errCode == -2013) {
                log.error("❌ SORGULAMA (-2013): Emir borsaya HİÇ ULAŞMAMIŞ veya gecikmiş.");
                updateRecordStatus(signalId, "UNKNOWN");
                throw new RuntimeException("ORDER_NOT_FOUND: Emir bulunamadı, durum BELİRSİZ.");
                
            } else if (errCode == -2015) {
                log.error("🚨 KRİTİK (-2015): Sorgulama için yetki reddedildi. Emrin durumu HÂLÂ BELİRSİZ (UNKNOWN)!");
                updateRecordStatus(signalId, "UNKNOWN");
                throw new RuntimeException("TIMEOUT_UNKNOWN_STATE: Sorgu yetki hatası verdi, emir durumu belirsiz!");
                
            } else {
                log.error("🚨 SORGULAMA BİLİNMEYEN HATA ({}): {}", errCode, queryEx.getMessage());
                updateRecordStatus(signalId, "UNKNOWN");
                throw new RuntimeException("TIMEOUT_UNKNOWN_STATE: Sorgu başarısız: " + queryEx.getMessage());
            }
            
        } catch (Exception queryEx) {
            log.error("🚨 KRİTİK: Sorgu da yapılamadı, internet tamamen yok!");
            updateRecordStatus(signalId, "UNKNOWN");
            throw new RuntimeException("TIMEOUT_UNKNOWN_STATE: Bağlantı koptu, durum sorgulanamadı.");
        }
    }

    public String requeryOrderStatus(String signalId) {
        SignalRecord record = signalRepository.findById(signalId)
                .orElseThrow(() -> new IllegalArgumentException("NOT_FOUND: '" + signalId + "' ID'li sinyal kaydı bulunamadı!"));

        String currentStatus = record.getOrderStatus();
        boolean isPendingOrUnknown = "UNKNOWN".equalsIgnoreCase(currentStatus)
                || "NEW".equalsIgnoreCase(currentStatus)
                || "PARTIALLY_FILLED".equalsIgnoreCase(currentStatus);

        if (!isPendingOrUnknown) {
            return currentStatus;
        }

        try {
            LinkedHashMap<String, Object> queryParams = new LinkedHashMap<>();
            queryParams.put("symbol", record.getSymbol());
            queryParams.put("origClientOrderId", signalId);
            queryParams.put("recvWindow", 60000L);

            String response = binanceClient.createTrade().getOrder(queryParams);
            JsonNode root = objectMapper.readTree(response);
            String newStatus = root.has("status") ? root.path("status").asText() : "UNKNOWN";

            record.setOrderStatus(newStatus);
            signalRepository.save(record);

            log.info("✅ YENİDEN SORGULAMA BAŞARILI: '{}' ID'li emrin güncel durumu: {}", signalId, newStatus);
            return newStatus;

        } catch (com.binance.connector.client.exceptions.BinanceClientException e) {
            if (e.getErrorCode() == -2013) {
                log.warn("Sorgulama başarısız (-2013), emir henüz bulunamadı. Durum UNKNOWN olarak korunuyor.");
                return "UNKNOWN";
            }
            throw e;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("JSON_PARSE_ERROR: " + e.getMessage(), e);
        }
    }

    private void updateRecordStatus(String signalId, String newStatus) {
        signalRepository.findById(signalId).ifPresent(record -> {
            record.setOrderStatus(newStatus);
            signalRepository.save(record);
        });
    }
}
