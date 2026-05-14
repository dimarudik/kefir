package dev.kefir;

import dev.kefir.model.Instrument;
import dev.kefir.repository.StateRepository;
import dev.kefir.service.TelegramNotificationService;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

        String tgToken = "8642692872:AAE0OUJxLTo8cQ296NMPQ_aQrxW1D8Msw6M";
        String tgChatId = "510693094";

        TelegramNotificationService tgService = new TelegramNotificationService(tgToken, tgChatId);

        String sharedLongAcc = "035982fd-8c31-4915-b26a-479d26974151";
        String sharedShortAcc = "fc29fd38-edc4-43b4-86c8-3839ed254628";
        boolean isSandbox = true; // Ваша настройка
        String host = isSandbox ? "sandbox-invest-public-api.tinkoff.ru" : "invest-public-api.tinkoff.ru";

        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(host, 443)
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .useTransportSecurity()
                .build();

        StateRepository stateRepository = new StateRepository();
        List<Instrument> instruments = loadInstruments();

        List<HedgeBot> activeBots = new CopyOnWriteArrayList<>();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.warn("[SHUTDOWN] Получен сигнал прерывания (Ctrl+C / SIGTERM). Начинаю безопасное сохранение...");

            // 1. Отправляем алерт в Telegram
            tgService.sendNotification("⚠️ *Внимание:* Сервер HedgeBot принудительно останавливается (Ctrl+C / kill). Срочно сохраняю стейты ботов.");

            // 2. Печатаем финансовый итог дня в лог перед выходом
            printSummary(activeBots);

            // 3. Вызываем saveState для каждого активного бота
            for (HedgeBot bot : activeBots) {
                try {
                    bot.saveState();
                    logger.info("[SHUTDOWN] Состояние для [{}] успешно зафиксировано в файле.", bot.getInstrument().ticker());
                } catch (Exception e) {
                    logger.error("[SHUTDOWN] Ошибка сохранения стейта для [{}]: {}", bot.getInstrument().ticker(), e.getMessage());
                }
            }

            // 4. Закрываем сетевое соединение с Т-Банком
            try {
                channel.shutdownNow();
                logger.info("[SHUTDOWN] gRPC канал успешно закрыт. Завершение работы процесса.");
            } catch (Exception e) {
                // Игнорируем
            }
        }));

        for (dev.kefir.model.Instrument i : instruments) {
            new Thread(() -> {
                HedgeBot bot = createBot(channel, i, token, stateRepository, sharedLongAcc, sharedShortAcc, isSandbox, tgService);
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
            Thread.sleep(2_000);
        }

        StringBuilder startMessage = new StringBuilder("🤖 *Центральный процессор HedgeBot запущен!*\n\n");
        startMessage.append(String.format("• Режим: `%s`\n", isSandbox ? "Песочница (Sandbox)" : "Боевой (Production)"));
        startMessage.append("• Статус gRPC: `Подключено`\n");
        startMessage.append("• Активные инструменты на дежурстве:\n");

        for (dev.kefir.model.Instrument i : instruments) {
            startMessage.append(String.format("   ▫️ *%s* — объем: `%d лотов` (порог: %d пушей)\n",
                    i.ticker(), i.quantity(), i.badPushThreshold()));
        }

        tgService.sendNotification(startMessage.toString());

        // Логика завершения дня
        while (true) {
            LocalTime now = LocalTime.now();
            // Например, закрываемся в 18:45 МСК
            if (now.isAfter(LocalTime.of(21, 30))) {
                logger.info("Время торговой сессии истекло. Закрываем день...");
                for (HedgeBot bot : activeBots) {
                    bot.stopAndClear();
                    Thread.sleep(2_000);
                }
                logger.info("Все позиции закрыты. Выход.");
                System.exit(0); // Завершаем программу
            }
            Thread.sleep(60_000); // Проверяем время раз в минуту
        }

/*
        // Реинициализация остатка + закрытие/открытие счетов
        {
            Instrument t = new Instrument("LKOH", "BBG004731032", 1, 1,2.0, 1.8, 1, 1, 0, 0);
            HedgeBot bot = createBot(channel, t, token, stateRepository, sharedLongAcc, sharedShortAcc, isSandbox, tgService);
            bot.performFullReset(50_000);
        }
*/

/*
        // Закрытие открытых позиций
        for (Instrument i : instruments) {
            new Thread(() -> {
                // Создаем отдельный экземпляр со своими уровнями и ATR, но общими счетами
                HedgeBot bot = createBot(channel, i, token, stateRepository, sharedLongAcc, sharedShortAcc, isSandbox);
                bot.stopAndClear();
            }).start();
            Thread.sleep(2_000);
        }
*/


/*
        // Печать истории операций
        for (Instrument i : instruments) {
            HedgeBot bot = createBot(channel, i, token, stateRepository, sharedLongAcc, sharedShortAcc, isSandbox);
            Instant to = LocalDate.now()
                    .atTime(18, 0) // время
                    .atZone(ZoneId.systemDefault()) // ваш часовой пояс
                    .toInstant();
            Instant from = to.minus(540, ChronoUnit.MINUTES);
            bot.printOperations(bot.getAccountIdLong(), from, to);
        }

        //  Печать позиций
        for (Instrument i : instruments) {
            HedgeBot bot = createBot(channel, i, token, stateRepository, sharedLongAcc, sharedShortAcc, isSandbox);
            bot.printPortfolioByFigi(bot.getAccountIdLong());
            bot.printPortfolioByFigi(bot.getAccountIdShort());
        }

        // Печать портфеля
        Instrument t = new Instrument("LKOH", "BBG004731032", 1, 1, 2.0, 1.8, 1, 1, 1, 1);
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
            boolean isSandbox,
            TelegramNotificationService tgService) {

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
                shortAcc,
                tgService);
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