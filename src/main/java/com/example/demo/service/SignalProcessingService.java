package com.example.demo.service;

import com.binance.connector.client.exceptions.BinanceClientException;
import com.binance.connector.client.exceptions.BinanceConnectorException;
import com.binance.connector.client.impl.SpotClientImpl;
import com.example.demo.dto.TradingSignalRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
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

        // SİNYALE BENZERSİZ BİR KİMLİK (İMZA) OLUŞTUR
        String signalKey = signal.symbol() + "_" + signal.action();
        long currentTime = System.currentTimeMillis();

        // KALKAN KONTROLÜ: Bu sinyal son 10 saniye (10000 ms) içinde geldi mi?
        if (processedSignals.containsKey(signalKey)) {
            long lastProcessedTime = processedSignals.get(signalKey);
            long timeDifference = currentTime - lastProcessedTime;

            if (timeDifference < 10000) { // 10 saniye
                log.warn("🚨 ÇİFT İŞLEM ENGELLENDİ! '{}' için henüz {} ms önce işlem yapıldı. Yankı (Tekrar) sinyali çöpe atılıyor.",
                        signalKey, timeDifference);
                // İşlemi durdur ve Controller'a hatayı fırlat
                throw new RuntimeException("Çift işlem koruması: Bu sinyal son 10 saniye içinde zaten işlendi!");
            }
        }

        // KALKANDAN GEÇERSE: Sinyalin işlenme saatini hafızaya kaydet
        processedSignals.put(signalKey, currentTime);

        log.info("Yeni TradingView Sinyali İşleniyor -> Parite: {}, Yön (Side): {}, Tetiklenme Fiyatı: {}",
                signal.symbol(), signal.action(), signal.price());

        sendMarketOrder(signal);
    }

    /**
     * Binance Testnet Spot API'sine SPOT MARKET emri gönderir.
     *
     * @param signal Sinyal DTO'su
     */
    private void sendMarketOrder(TradingSignalRequest signal) {
        // 2. DÜZELTME: İki tane olan sendMarketOrder metodu teke düşürüldü ve içeriği toparlandı.
        try {
            SpotClientImpl client = new SpotClientImpl(apiKey, secretKey, TESTNET_BASE_URL);

            LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("symbol", signal.symbol());
            parameters.put("side", signal.action());
            parameters.put("type", "MARKET");

            // SABİT 0.01 YERİNE ARTIK DIŞARIDAN GELEN MİKTARI KULLANIYORUZ
            parameters.put("quantity", signal.quantity());

            log.info("Binance Testnet'e MARKET Emri Gönderiliyor... [Parite: {}, Yön: {}, Miktar: {}]",
                    signal.symbol(), signal.action(), signal.quantity());

            String response = client.createTrade().newOrder(parameters);
            log.info("Binance Emri Başarıyla Gerçekleşti! Dönen JSON Cevabı:\n{}", response);

        } catch (BinanceClientException e) {
            log.error("Binance İstemci Hatası (Client Exception) oluştu! HTTP Kodu: {}, Hata Mesajı: {}",
                    e.getHttpStatusCode(), e.getMessage(), e);
            throw new RuntimeException("Binance Emri Reddetti: " + e.getMessage());

        } catch (BinanceConnectorException e) {
            log.error("Binance Bağlantı Hatası (Connector Exception) oluştu! Hata Mesajı: {}",
                    e.getMessage(), e);
            throw new RuntimeException("Binance Bağlantı Hatası: " + e.getMessage());

        } catch (Exception e) {
            log.error("Emir gönderimi sırasında beklenmeyen bir hata meydana geldi: {}",
                    e.getMessage(), e);
            throw new RuntimeException("Sistem Hatası: " + e.getMessage());
        }
    }
}