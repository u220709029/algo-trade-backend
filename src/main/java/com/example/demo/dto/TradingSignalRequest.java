package com.example.demo.dto;

import java.math.BigDecimal;

/**
 * TradingView üzerinden gelen anlık AL/SAT webhook sinyallerini karşılayan DTO.
 * Java 17+ Record yapısı kullanılarak immutable (değiştirilemez) ve thread-safe olarak tasarlanmıştır.
 *
 * @param symbol    İşlem yapılacak parite (Örn: "BTCUSDT", "ETHUSDT")
 * @param action    Gerçekleştirilecek aksiyon (Örn: "BUY", "SELL")
 * @param price     Sinyalin tetiklendiği fiyat (Finansal hesaplamalarda yuvarlama ve hassasiyet kaybını önlemek için BigDecimal)
 * @param timestamp Sinyalin oluşturulduğu Unix zaman damgası (milisaniye cinsinden)
 */
public record TradingSignalRequest(
    String symbol,
    String action,
    BigDecimal price,
    Long timestamp,
    BigDecimal quantity
) {
}
