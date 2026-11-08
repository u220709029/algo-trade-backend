package com.example.demo.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binance REST & WebSocket API entegrasyonu için yapılandırma (configuration) sınıfı.
 * 'application.properties' ya da 'application.yml' içerisindeki 'binance.api.*'
 * konfigürasyonlarını tip güvenli (type-safe) bir şekilde inject eder.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "binance.api")
public class BinanceApiConfig {

    /**
     * Binance Spot / Futures API anahtarı
     */
    private String apiKey;

    /**
     * Binance Secret anahtarı (HMAC-SHA256 imzalamaları için kullanılır)
     */
    private String secretKey;

    /**
     * Binance Base URL (Örn: Canlı: https://api.binance.com | Testnet: https://testnet.binance.vision)
     */
    private String baseUrl = "https://testnet.binance.vision";

    /**
     * Testnet aktif mi bayrağı (Gerçek parayla işlem yapmadan önce test ortamı güvencesi)
     */
    private boolean testnet = true;

    // İleride Binance SDK, WebClient veya RestClient Bean tanımlamaları buraya eklenecektir.
}
