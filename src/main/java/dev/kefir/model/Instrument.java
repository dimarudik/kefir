package dev.kefir.model;

public record Instrument(
        String ticker,
        String figi,
        int quantity,
        int lotSize,
        double atrMultiplier,
        double levelMultiplier,
        int badPushThreshold,
        int stopPushThreshold,
        float commission,
        double rangeCompression) {
}
