package dev.kefir;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws InterruptedException {
        if (args.length < 1) {
            System.err.println("Ошибка: Необходимо передать API токен первым аргументом.");
            System.err.println("Пример запуска: java -jar bot.jar <TOKEN>");
            System.exit(1);
        }

        String token = args[0];
//        String figi = "TCS80A107UL4"; // Т-Банк

        String sharedLongAcc = "d16e6396-a380-481c-8150-88b5d782d995";
        String sharedShortAcc = "4dd2fd7d-b34d-4e82-8995-79c80c88d9c8";

        List<Instrument> instruments = List.of(
                new Instrument("VTBR","BBG004730ZJ9", 30), // ВТБ
                new Instrument("SBER", "BBG004730N88", 10), // Сбербанк
                new Instrument("LKOH", "BBG004731032", 1)   // Лукойл
                );

        List<HedgeBot> activeBots = new ArrayList<>();

        for (Instrument i : instruments) {
            new Thread(() -> {
                // Создаем отдельный экземпляр со своими уровнями и ATR, но общими счетами
                HedgeBot bot = new HedgeBot(i, token, sharedLongAcc, sharedShortAcc, true);
                activeBots.add(bot);
                bot.printPortfolio(bot.getAccountIdLong());
                bot.printPortfolio(bot.getAccountIdShort());

                bot.initLevels(i.figi());

                if (!bot.tryAttachToExistingHedge()) {
                    bot.openHedge(bot.getAccountIdLong(), bot.getAccountIdShort());
                }

                bot.subscribeCandles();
            }).start();
            Thread.sleep(2000);
        }

/*
        // Логика завершения дня
        while (true) {
            LocalTime now = LocalTime.now();
            // Например, закрываемся в 18:45 МСК
            if (now.isAfter(LocalTime.of(18, 45))) {
                System.out.println("Время торговой сессии истекло. Закрываем день...");
                for (HedgeBot bot : activeBots) {
                    bot.stopAndClear();
                }
                System.out.println("Все позиции закрыты. Выход.");
                System.exit(0); // Завершаем программу
            }
            Thread.sleep(60000); // Проверяем время раз в минуту
        }
*/

/*
        // Принудительное закрытие позиций бота
        for (Instrument i : instruments) {
            new Thread(() -> {
                // Создаем отдельный экземпляр со своими уровнями и ATR, но общими счетами
                HedgeBot bot = new HedgeBot(token, sharedLongAcc, sharedShortAcc, true);
                bot.stopAndClear();
                logger.info("Все позиции закрыты. Выход.");
                System.exit(0);
            }).start();
        }
*/

/*
        HedgeBot bot = new HedgeBot(token, sharedLongAcc, sharedShortAcc, true);
        bot.printPortfolio(bot.getAccountIdLong());
        bot.printPortfolio(bot.getAccountIdShort());
*/

/*
        HedgeBot bot = new HedgeBot(token, sharedLongAcc, sharedShortAcc, true);
        bot.printPortfolio(bot.getAccountIdLong());
        bot.printPortfolio(bot.getAccountIdShort());

        // Очищаем счета от старых "замков" (например, Т-Банка)
        bot.closeAllPositions(bot.getAccountIdLong());
        bot.closeAllPositions(bot.getAccountIdShort());
        // Обнуляем балансы
        bot.resetBalanceToZero(bot.getAccountIdLong());
        bot.resetBalanceToZero(bot.getAccountIdShort());

        bot.printPortfolio(bot.getAccountIdLong());
        bot.printPortfolio(bot.getAccountIdShort());

        // Подготавливаем окружение (счета и деньги)
        bot.prepareSandboxAccounts();
        bot.printPortfolio(bot.getAccountIdLong());
        bot.printPortfolio(bot.getAccountIdShort());
*/

        Thread.currentThread().join();
    }
}

record Instrument(String ticker, String figi, int quantity){}