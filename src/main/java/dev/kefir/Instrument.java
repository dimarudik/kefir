package dev.kefir;

public record Instrument(String ticker, String figi, int quantity, double atrMultiplier) {
}
