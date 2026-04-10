package dev.kefir;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws InterruptedException {
        if (args.length < 1) {
            System.err.println("Ошибка: Необходимо передать API токен первым аргументом.");
            System.err.println("Пример запуска: java -jar bot.jar <TOKEN>");
            System.exit(1);
        }

        String token = args[0];
        String figi = "BBG004733333"; // Т-Банк

        String myRealLongAcc = "d16e6396-a380-481c-8150-88b5d782d995";
        String myRealShortAcc = "4dd2fd7d-b34d-4e82-8995-79c80c88d9c8";

        // Передаем null в качестве ID счетов, так как prepareSandboxAccounts их заполнит
        HedgeBot bot = new HedgeBot(token, null, null, true);
        bot.printRealAccounts();

        // Подготавливаем окружение (счета и деньги)
        bot.prepareSandboxAccounts();

        // Запускаем стандартный цикл
        bot.initLevels(figi);
        bot.openHedge(figi, 1, bot.getAccountIdLong(), bot.getAccountIdShort());
        bot.subscribeCandles(figi);

        Thread.currentThread().join();
    }
}
