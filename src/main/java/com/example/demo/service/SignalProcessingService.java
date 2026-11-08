package com.example.demo.service;

import com.binance.connector.client.exceptions.BinanceClientException;
import com.binance.connector.client.exceptions.BinanceConnectorException;
import com.binance.connector.client.impl.SpotClientImpl;
import com.example.demo.dto.TradingSignalRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;

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

    /**
     * Webhook'tan gelen trading sinyalini karşılar ve Binance Testnet ağı üzerinde
     * MARKET emri oluşturur.
     *
     * @param signal TradingView tarafından gönderilen sinyal verisi (symbol, action vb.)
     */
    public void processSignal(TradingSignalRequest signal) {
        log.info("Yeni TradingView Sinyali İşleniyor -> Parite: {}, Yön (Side): {}, Tetiklenme Fiyatı: {}, Zaman Damgası: {}",
                signal.symbol(),
                signal.action(),
                signal.price(),
                signal.timestamp());

        sendMarketOrder(signal);
    }

    /**
     * Binance Testnet Spot API'sine SPOT MARKET emri gönderir.
     *
     * @param signal Sinyal DTO'su
     */
    private void sendMarketOrder(TradingSignalRequest signal) {
        try {
            // Binance SpotClient nesnesi Testnet URL'i ile ilklendiriliyor
            SpotClientImpl client = new SpotClientImpl(apiKey, secretKey, TESTNET_BASE_URL);

            // Binance API kurallarına uygun parametre haritası (LinkedHashMap)
            LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("symbol", signal.symbol());
            parameters.put("side", signal.action()); // BUY veya SELL
            parameters.put("type", "MARKET");
            parameters.put("quantity", 0.01);

            log.info("Binance Testnet'e MARKET Emri Gönderiliyor... [Parite: {}, Yön: {}, Miktar: 0.01]",
                    signal.symbol(), signal.action());

            // Emri gönder ve dönen JSON yanıtını al
            String response = client.createTrade().newOrder(parameters);

            log.info("Binance Emri Başarıyla Gerçekleşti! Dönen JSON Cevabı:\n{}", response);

        } catch (BinanceClientException e) {
            // 4xx HTTP hataları (ör. Yetersiz bakiye, geçersiz sembol, lot size kuralı ihlali vb.)
            log.error("Binance İstemci Hatası (Client Exception) oluştu! HTTP Kodu: {}, Hata Mesajı: {}",
                    e.getHttpStatusCode(), e.getMessage(), e);
        } catch (BinanceConnectorException e) {
            // Bağlantı, ağ, zaman aşımı veya HMAC imzalama hataları
            log.error("Binance Bağlantı Hatası (Connector Exception) oluştu! Hata Mesajı: {}",
                    e.getMessage(), e);
        } catch (Exception e) {
            // Beklenmeyen genel hatalar
            log.error("Emir gönderimi sırasında beklenmeyen bir hata meydana geldi: {}",
                    e.getMessage(), e);
        }
    }
}
