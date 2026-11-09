package com.example.demo.service;

import com.binance.connector.client.exceptions.BinanceClientException;
import com.binance.connector.client.exceptions.BinanceConnectorException;
import com.binance.connector.client.impl.SpotClientImpl;
import com.example.demo.dto.TradingSignalRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TradingView webhook sinyallerini işleyerek doğrudan Binance Testnet API'sine
 * gerçek zamanlı emir iletimi gerçekleştiren servis katmanı.
 */
@Slf4j
@Service
public class SignalProcessingService {

    private static final String TESTNET_BASE_URL = "https://testnet.binance.vision";

    @Value("${binance.api.key}")
    private String apiKey;

    @Value("${binance.api.secret}")
    private String secretKey;

    // 1. DÜZELTME: Çift işlem kalkanı için geçici hafıza burada doğru şekilde tanımlandı.
    private final ConcurrentHashMap<String, Long> processedSignals = new ConcurrentHashMap<>();

    /**
     * Webhook'tan gelen trading sinyalini karşılar ve Binance Testnet ağı üzerinde MARKET emri oluşturur.
     *
     * @param signal TradingView tarafından gönderilen sinyal verisi
     */
    public void processSignal(TradingSignalRequest signal) {

        if (signal.signalId() == null || signal.signalId().trim().isEmpty()) {
            log.error("🚨 GÜVENLİK İHLALİ: Sinyal Kimliği (signalId) eksik!");
            throw new IllegalArgumentException("VALIDATION_ERROR: signalId zorunludur. Kimliksiz işlem kabul edilemez.");
        }
        String signalId = signal.signalId();

        // B) Miktar Kontrolü
        if (signal.quantity().compareTo(BigDecimal.ZERO) <= 0 || signal.quantity().compareTo(new BigDecimal("10")) > 0) {
            throw new IllegalArgumentException("VALIDATION_ERROR: Miktar 0'dan büyük ve 10'dan küçük olmalıdır.");
        }

        // C) Parite Kontrolü: Güvenlik için sadece USDT (Tether) paritelerinde işlem yapılmasına izin ver
        if (signal.symbol() == null || !signal.symbol().toUpperCase().endsWith("USDT")) {
            log.error("🚨 GÜVENLİK İHLALİ: Desteklenmeyen parite. Parite: {}", signal.symbol());
            throw new IllegalArgumentException("VALIDATION_ERROR: Sadece USDT pariteleri desteklenmektedir.");
        }

//        // 1. EĞER TRADINGVIEW ID GÖNDERMEYİ UNUTURSA, BİZ RASTGELE ÜRETELİM
//        String signalId = signal.signalId();
//        if (signalId == null || signalId.isEmpty()) {
//            signalId = UUID.randomUUID().toString();
//        }

        long currentTime = System.currentTimeMillis();

        // 2. ATOMİK KONTROL (RACE CONDITION ÇÖZÜMÜ)
        // putIfAbsent: Eğer bu ID hafızada YOKSA ekler ve 'null' döner. VARSA eski değerini döner.
        Long previousTime = processedSignals.putIfAbsent(signalId, currentTime);

        if (previousTime != null) {
            log.warn("🚨 ÇİFT İŞLEM ENGELLENDİ! '{}' ID'li sinyal zaten işlendi.", signalId);
            // Hata tipini belli etmek için özel bir mesaj fırlatıyoruz
            throw new RuntimeException("IDEMPOTENCY_ERROR: Bu sinyal zaten işlendi!");
        }

        log.info("Yeni Sinyal İşleniyor -> Parite: {}, Yön: {}, Miktar: {}, ID: {}",
                signal.symbol(), signal.action(), signal.quantity(), signalId);

        // ID'yi Binance'e iletilmek üzere metoda gönderiyoruz
        sendMarketOrder(signal, signalId);
    }

    private void sendMarketOrder(TradingSignalRequest signal, String signalId) {

        // DÜZELTME: client nesnesi try bloğunun DIŞINA taşındı. Artık catch bloğu da bunu görebilir.
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
            log.info("Binance Emri Başarıyla Gerçekleşti! Dönen JSON:\n{}", response);

        } catch (BinanceClientException e) {
            throw new RuntimeException("BINANCE_REJECTED: " + e.getMessage());

        } catch (BinanceConnectorException e) {
            log.warn("🚨 İNTERNET KOPTU VEYA ZAMAN AŞIMI! Emrin durumu belirsiz. Binance'e '{}' ID'si ile soruluyor...", signalId);

            try {
                LinkedHashMap<String, Object> queryParams = new LinkedHashMap<>();
                queryParams.put("symbol", signal.symbol());
                queryParams.put("origClientOrderId", signalId);
                queryParams.put("recvWindow", 60000L);

                // Artık client değişkeni burada hata vermeyecek
                String orderStatusResponse = client.createTrade().getOrder(queryParams);

                log.info("✅ KORKULACAK BİR ŞEY YOK! Emir Binance'e başarıyla ulaşmış. Durumu:\n{}", orderStatusResponse);

            } catch (BinanceClientException queryEx) {
                log.error("❌ SORGULAMA SONUCU: Emir Binance'e hiç ulaşmamış. Hata: {}", queryEx.getMessage());
                throw new RuntimeException("ORDER_NOT_FOUND: İnternet koptu ve emir borsaya hiç ulaşmadı.");

            } catch (Exception queryEx) {
                log.error("🚨 KRİTİK: Sorgu da başarısız oldu! Bağlantı tamamen gitmiş: {}", queryEx.getMessage());
                throw new RuntimeException("TIMEOUT_UNKNOWN_STATE: Bağlantı tamamen koptu, durum sorgulanamadı.");
            }

        } catch (Exception e) {
            log.error("Emir gönderimi sırasında beklenmeyen bir hata meydana geldi: {}", e.getMessage(), e);
            throw new RuntimeException("SYSTEM_ERROR: " + e.getMessage());
        }
    }
}