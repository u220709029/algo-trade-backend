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

        if (signal.quantity().compareTo(BigDecimal.ZERO) <= 0 || signal.quantity().compareTo(new BigDecimal("10")) > 0) {
            log.error("🚨 GÜVENLİK İHLALİ: Geçersiz miktar algılandı. Miktar: {}", signal.quantity());
            throw new IllegalArgumentException("VALIDATION_ERROR: Miktar 0'dan büyük ve 10'dan küçük olmalıdır.");
        }

        // B) Yön Kontrolü: Sadece BUY veya SELL komutlarına izin ver
        if (!"BUY".equalsIgnoreCase(signal.action()) && !"SELL".equalsIgnoreCase(signal.action())) {
            log.error("🚨 GÜVENLİK İHLALİ: Geçersiz işlem yönü. Yön: {}", signal.action());
            throw new IllegalArgumentException("VALIDATION_ERROR: İşlem yönü sadece BUY veya SELL olabilir.");
        }

        // C) Parite Kontrolü: Güvenlik için sadece USDT (Tether) paritelerinde işlem yapılmasına izin ver
        if (signal.symbol() == null || !signal.symbol().toUpperCase().endsWith("USDT")) {
            log.error("🚨 GÜVENLİK İHLALİ: Desteklenmeyen parite. Parite: {}", signal.symbol());
            throw new IllegalArgumentException("VALIDATION_ERROR: Sadece USDT pariteleri desteklenmektedir.");
        }

        // 1. EĞER TRADINGVIEW ID GÖNDERMEYİ UNUTURSA, BİZ RASTGELE ÜRETELİM
        String signalId = signal.signalId();
        if (signalId == null || signalId.isEmpty()) {
            signalId = UUID.randomUUID().toString();
        }

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
        try {
            SpotClientImpl client = new SpotClientImpl(apiKey, secretKey, TESTNET_BASE_URL);

            LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("symbol", signal.symbol());
            parameters.put("side", signal.action());
            parameters.put("type", "MARKET");
            parameters.put("quantity", signal.quantity());

            // 3. SCHRÖDİNGER ÇÖZÜMÜ: Binance'e kendi fiş numaramızı (ID) veriyoruz
            parameters.put("newClientOrderId", signalId);

            String response = client.createTrade().newOrder(parameters);
            log.info("Binance Emri Başarıyla Gerçekleşti! Dönen JSON:\n{}", response);

        } catch (BinanceClientException e) {
            // Binance kurallarına aykırı bir şey yaptık (Bakiye yok, limit altı vs.)
            throw new RuntimeException("BINANCE_REJECTED: " + e.getMessage());

        } catch (BinanceConnectorException e) {
            // İNTERNET KOPTU! Binance emri aldı mı almadı mı bilmiyoruz!
            throw new RuntimeException("TIMEOUT_UNKNOWN_STATE: İnternet koptu, emrin durumu belirsiz.");

        } catch (Exception e) {
            throw new RuntimeException("SYSTEM_ERROR: " + e.getMessage());
        }
    }
}