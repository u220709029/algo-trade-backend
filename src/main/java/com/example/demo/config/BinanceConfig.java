package com.example.demo.config;

import com.binance.connector.client.impl.SpotClientImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BinanceConfig {

    @Value("${binance.api.key:}")
    private String apiKey;

    @Value("${binance.api.secret:}")
    private String secretKey;

    @Value("${binance.api.base-url:https://testnet.binance.vision}")
    private String baseUrl;

    @Bean
    public SpotClientImpl spotClient() {
        return new SpotClientImpl(apiKey, secretKey, baseUrl);
    }
}
