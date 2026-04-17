package dev.kefir;

import dev.kefir.model.Instrument;
import dev.kefir.repository.StateRepository;
import dev.kefir.service.TinkoffApiService;
import dev.kefir.service.TokenInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tinkoff.piapi.contract.v1.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws InterruptedException {
        if (args.length < 1) {
            System.err.println("Ошибка: Необходимо передать API токен первым аргументом.");
            System.err.println("Пример запуска: java -jar bot.jar <TOKEN>");
            System.exit(1);
        }

        String token = args[0];

        String sharedLongAcc = "d16e6396-a380-481c-8150-88b5d782d995";
        String sharedShortAcc = "4dd2fd7d-b34d-4e82-8995-79c80c88d9c8";
        boolean isSandbox = true; // Ваша настройка
        String host = isSandbox ? "sandbox-invest-public-api.tinkoff.ru" : "invest-public-api.tinkoff.ru";
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(host, 443)
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .useTransportSecurity()
                .build();

        StateRepository stateRepository = new StateRepository();

        List<Instrument> instruments = loadInstruments();

        List<HedgeBot> activeBots = new ArrayList<>();
        for (dev.kefir.model.Instrument i : instruments) {
            new Thread(() -> {
                HedgeBot bot = createBot(channel, i, token, stateRepository, sharedLongAcc, sharedShortAcc, isSandbox);
                bot.printPortfolioByFigi(bot.getAccountIdLong());
                bot.printPortfolioByFigi(bot.getAccountIdShort());
                activeBots.add(bot);

                bot.loadState();
                bot.initLevels(i.figi());

                if (!bot.tryAttachToExistingHedge()) {
                    bot.openHedge();
                }

                bot.subscribeCandles();
            }).start();
            Thread.sleep(2000);
        }
        Thread.currentThread().join();


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
        // Печать истории операций
        for (Instrument i : instruments) {
            HedgeBot bot = createBot(channel, i, token, stateRepository, sharedLongAcc, sharedShortAcc, isSandbox);
            Instant to = LocalDate.now()
                    .atTime(14, 15) // время
                    .atZone(ZoneId.systemDefault()) // ваш часовой пояс
                    .toInstant();
            Instant from = to.minus(2, ChronoUnit.HOURS);
            bot.printOperations(bot.getAccountIdLong(), from, to);
        }
*/

/*
        // Принудительное закрытие позиций бота + печать
        for (Instrument i : instruments) {
            HedgeBot bot = createBot(channel, i, token, stateRepository, sharedLongAcc, sharedShortAcc, isSandbox);
            bot.printPortfolioByFigi(bot.getAccountIdLong());
            bot.printPortfolioByFigi(bot.getAccountIdShort());
        }

        for (Instrument i : instruments) {
            new Thread(() -> {
                // Создаем отдельный экземпляр со своими уровнями и ATR, но общими счетами
                HedgeBot bot = createBot(channel, i, token, stateRepository, sharedLongAcc, sharedShortAcc, isSandbox);
                bot.stopAndClear();
            }).start();
            Thread.sleep(2_000);
        }

        for (Instrument i : instruments) {
            HedgeBot bot = createBot(channel, i, token, stateRepository, sharedLongAcc, sharedShortAcc, isSandbox);
            bot.printPortfolioByFigi(bot.getAccountIdLong());
            bot.printPortfolioByFigi(bot.getAccountIdShort());
        }
*/

/*
        for (Instrument i : instruments) {
            HedgeBot bot = createBot(channel, i, token, stateRepository, sharedLongAcc, sharedShortAcc, isSandbox);
            bot.printPortfolioByFigi(bot.getAccountIdLong());
            bot.printPortfolioByFigi(bot.getAccountIdShort());
        }
*/



/*
        // Реинициализация остатка
        Instrument t = new Instrument("LKOH", "BBG004731032", 1, 2.0, 1.8);
        HedgeBot bot = createBot(channel, t, token, stateRepository, sharedLongAcc, sharedShortAcc, isSandbox);
        // Обнуляем балансы
        bot.resetBalanceToZero(bot.getAccountIdLong());
        bot.resetBalanceToZero(bot.getAccountIdShort());

        // Подготавливаем окружение (счета и деньги)
        bot.prepareSandboxAccounts();
        bot.printPortfolio(bot.getAccountIdLong());
        bot.printPortfolio(bot.getAccountIdShort());
*/

/*
        // Печать портфеля
        Instrument t = new Instrument("LKOH", "BBG004731032", 1, 2.0, 1.8);
        HedgeBot bot = createBot(channel, t, token, stateRepository, sharedLongAcc, sharedShortAcc, isSandbox);
        bot.printPortfolio(bot.getAccountIdLong());
        bot.printPortfolio(bot.getAccountIdShort());
*/

    }

    private static void printSummary(List<HedgeBot> bots) {
        double total = 0;
        StringBuilder sb = new StringBuilder("\n=== FINANCIAL SUMMARY ===\n");
        for (HedgeBot b : bots) {
            double p = b.getTotalProfit();
            total += p;
            sb.append(String.format("[%s]: %.2f руб.\n", b.getInstrument().ticker(), p));
        }
        sb.append(String.format("TOTAL PnL: %.2f руб.\n", total));
        sb.append("=========================\n");
        logger.info(sb.toString());
    }

    private static HedgeBot createBot(
            ManagedChannel channel,
            Instrument instrument,
            String token,
            StateRepository stateRepository,
            String longAcc,
            String shortAcc,
            boolean isSandbox) {

        TokenInterceptor auth = new TokenInterceptor(token);

        // Создаем все стабы один раз
        var sandboxStub = SandboxServiceGrpc.newBlockingStub(channel).withCallCredentials(auth);
        var marketDataStub = MarketDataServiceGrpc.newBlockingStub(channel).withCallCredentials(auth);
        var marketDataStream = MarketDataStreamServiceGrpc.newStub(channel).withCallCredentials(auth);
        var ordersStub = OrdersServiceGrpc.newBlockingStub(channel).withCallCredentials(auth);
        var operationsStub = OperationsServiceGrpc.newBlockingStub(channel).withCallCredentials(auth);
        var userStub = UsersServiceGrpc.newBlockingStub(channel).withCallCredentials(auth);

        TinkoffApiService api = new TinkoffApiService(sandboxStub,ordersStub, operationsStub, marketDataStub, userStub, isSandbox);

        return new HedgeBot(
                instrument,
                api,
                stateRepository,
                marketDataStream,
                longAcc,
                shortAcc);
    }

    private static List<Instrument> loadInstruments() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            File file = new File("config.json");
            if (!file.exists()) {
                logger.error("Файл config.json не найден!");
                System.exit(1);
            }
            return mapper.readValue(file, new TypeReference<List<Instrument>>() {});
        } catch (Exception e) {
            logger.error("Ошибка при чтении конфига: {}", e.getMessage());
            System.exit(1);
            return null;
        }
    }
}