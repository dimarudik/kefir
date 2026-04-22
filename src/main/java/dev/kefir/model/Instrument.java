package dev.kefir.model;

public record Instrument(
        String ticker,
        String figi,
        int quantity,
        double atrMultiplier,
        double levelMultiplier,
        int badPushThreshold) {
}
