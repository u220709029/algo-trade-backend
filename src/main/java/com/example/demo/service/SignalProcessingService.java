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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SignalProcessingService {

    private static final String TESTNET_BASE_URL = "https://testnet.binance.vision";

    @Value("${binance.api.key}")
    private String apiKey;

    @Value("${binance.api.secret}")
    private String secretKey;

    private final SignalRepository signalRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void processSignal(TradingSignalRequest signal) {
        
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
        
        SignalRecord record = new SignalRecord(signalId, signal.symbol(), signal.action(), "UNKNOWN", System.currentTimeMillis());
        try {
            // saveAndFlush anında diske yazar. Eğer aynı ID ile eşzamanlı (concurrent) bir istek 
            // gelirse, veritabanı Primary Key kısıtlamasından dolayı hata fırlatır.
            signalRepository.saveAndFlush(record);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.warn("🚨 EŞZAMANLI ÇİFT İŞLEM ENGELLENDİ (Race Condition)! '{}' ID'li sinyal veritabanına yazılırken çakışma yaşandı.", signalId);
            throw new RuntimeException("IDEMPOTENCY_ERROR: Bu sinyal zaten işlendi!");
        }

        // --- 3. BORSAYA İLETİM ---
        sendMarketOrder(signal, signalId);
    }

    private void sendMarketOrder(TradingSignalRequest signal, String signalId) {
        SpotClientImpl client = new SpotClientImpl(apiKey, secretKey, TESTNET_BASE_URL);

        try {
            LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("symbol", signal.symbol());
            parameters.put("side", signal.action());
            parameters.put("type", "MARKET");
            parameters.put("quantity", signal.quantity());
            parameters.put("newClientOrderId", signalId);
            parameters.put("recvWindow", 60000L);

            String response = client.createTrade().newOrder(parameters);
            
            JsonNode root = objectMapper.readTree(response);
            String status = root.path("status").asText("FILLED");
            
            log.info("✅ Binance Emri Başarıyla Gerçekleşti! Durum: {}", status);
            updateRecordStatus(signalId, status);

        } catch (BinanceClientException e) {
            if (e.getErrorCode() == -1007) {
                checkOrderStatus(client, signal, signalId);
            } else {
                updateRecordStatus(signalId, "REJECTED_" + e.getErrorCode());
                throw new RuntimeException("BINANCE_REJECTED: " + e.getMessage());
            }

        } catch (BinanceConnectorException e) {
            checkOrderStatus(client, signal, signalId);
            
        } catch (Exception e) {
            updateRecordStatus(signalId, "SYSTEM_ERROR");
            throw new RuntimeException("SYSTEM_ERROR: " + e.getMessage());
        }
    }

    private void checkOrderStatus(SpotClientImpl client, TradingSignalRequest signal, String signalId) {
        log.warn("🚨 DURUM BELİRSİZ (-1007 veya Ağ Hatası). Binance'e '{}' ID'si ile akıllı sorgu atılıyor...", signalId);
        
        try {
            LinkedHashMap<String, Object> queryParams = new LinkedHashMap<>();
            queryParams.put("symbol", signal.symbol());
            queryParams.put("origClientOrderId", signalId);
            queryParams.put("recvWindow", 60000L);
            
            String response = client.createTrade().getOrder(queryParams);
            
            JsonNode root = objectMapper.readTree(response);
            String status = root.path("status").asText();
            
            log.info("✅ SORGULAMA BAŞARILI: Emir Binance'e ulaşmış. Gerçek Durumu: {}", status);
            updateRecordStatus(signalId, status);
            
        } catch (BinanceClientException queryEx) {
            int errCode = queryEx.getErrorCode();
            
            if (errCode == -2013) {
                log.error("❌ SORGULAMA (-2013): Emir borsaya HİÇ ULAŞMAMIŞ.");
                updateRecordStatus(signalId, "FAILED_NEVER_REACHED");
                throw new RuntimeException("ORDER_NOT_FOUND: İnternet koptu ve emir borsaya hiç ulaşmadı.");
                
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

    private void updateRecordStatus(String signalId, String newStatus) {
        signalRepository.findById(signalId).ifPresent(record -> {
            record.setOrderStatus(newStatus);
            signalRepository.save(record);
        });
    }
}
