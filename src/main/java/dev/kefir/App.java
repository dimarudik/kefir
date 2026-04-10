package dev.kefir;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws InterruptedException {
        String token = "ВАШ_ТОКЕН_ПЕСОЧНИЦЫ";
        String figi = "BBG004733333"; // Т-Банк

        String myRealLongAcc = "2000123456";
        String myRealShortAcc = "2000654321";

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
