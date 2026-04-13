package dev.kefir;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

        List<String> instruments = List.of(
                "BBG004730N88", // Сбербанк
                "BBG004731032"  // Лукойл
        );

        for (String figi : instruments) {
            new Thread(() -> {
                // Создаем отдельный экземпляр со своими уровнями и ATR, но общими счетами
                HedgeBot bot = new HedgeBot(token, sharedLongAcc, sharedShortAcc, true);
                bot.printPortfolio(bot.getAccountIdLong());
                bot.printPortfolio(bot.getAccountIdShort());

                bot.initLevels(figi);

                if (!bot.tryAttachToExistingHedge(figi)) {
                    bot.openHedge(figi, 1, bot.getAccountIdLong(), bot.getAccountIdShort());
                }

                bot.subscribeCandles(figi);
            }).start();
        }


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
