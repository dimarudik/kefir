package dev.kefir.model;

public enum BotStatus {
    INITIALIZING, // Загрузка свечей, расчет ATR
    LOCKED,       // Замок открыт (isLocked = true)
    TRAILING,     // Идет трейлинг (isLongActive или isShortActive)
    PAUSE         // Та самая минута отдыха между кругами
}
