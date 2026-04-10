package dev.kefir;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws InterruptedException {
        String token = "ВАШ_ТОКЕН";
        String accLong = "ID_ПЕРВОГО_СЧЕТА";
        String accShort = "ID_ВТОРОГО_СЧЕТА";
        String figi = "BBG004733333"; // Т-Банк

        HedgeBot bot = new HedgeBot(token, accLong, accShort, true);

        // 1. Инициализируем уровни на основе истории (предыдущие 4 часа)
        bot.initLevels(figi);

        // 2. Открываем "Замок" (по 1 акции для теста)
        bot.openHedge(figi, 1, accLong, accShort);

        // 3. Подписываемся на 5-минутные свечи
        bot.subscribeCandles(figi);

        logger.info("Робот запущен и ожидает закрытия 5-минутных свечей...");

        // Чтобы main-поток не завершился сразу
        Thread.currentThread().join();
    }
}
