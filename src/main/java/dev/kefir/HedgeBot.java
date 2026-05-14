package dev.kefir;

import com.google.protobuf.Timestamp;
import dev.kefir.model.BotState;
import dev.kefir.model.BotStatus;
import dev.kefir.model.Instrument;
import dev.kefir.repository.StateRepository;
import dev.kefir.service.TelegramNotificationService;
import dev.kefir.service.TinkoffApiService;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tinkoff.piapi.contract.v1.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static dev.kefir.service.TinkoffApiService.getStackTrace;

public class HedgeBot {
    private static final Logger logger = LoggerFactory.getLogger(HedgeBot.class);

    private final TelegramNotificationService tgService;
    private final TinkoffApiService api;
    private final StateRepository repository;
    private final MarketDataStreamServiceGrpc.MarketDataStreamServiceStub marketDataStreamStub;
    private StreamObserver<MarketDataRequest> currentRequestObserver;

    private int breakoutPushCount = 0;
    private int stopViolationCount = 0;
    private double lastBreakoutPrice = 0;

    // Параметры инструмента
    private final dev.kefir.model.Instrument instrument;
    private final String accountIdLong;
    private final String accountIdShort;
    private final double atrMultiplier;
    private final long currentQuantity;

    private long lastPushTimestamp = 0;
    private double pushesPerSecond = 0; // Наша системная переменная

    // Состояние бота (volatile для многопоточности)
    private volatile double supportLevel;
    private volatile double resistanceLevel;
    private volatile double trailingStopPrice;
    private volatile double lastAtr;
    private volatile boolean isLocked;
    private volatile boolean isLongActive;
    private volatile boolean isShortActive;
    private volatile double lastClosedPrice;
    private volatile double longEntryPrice;
    private volatile double shortEntryPrice;
    private volatile double totalProfit;
    private long lastLogTime = 0;
    private volatile BotStatus status = BotStatus.INITIALIZING;

    public HedgeBot(Instrument instrument,
                    TinkoffApiService api,
                    StateRepository repository,
                    MarketDataStreamServiceGrpc.MarketDataStreamServiceStub marketDataStreamStub,
                    String accountIdLong,
                    String accountIdShort,
                    TelegramNotificationService tgService) {
        this.instrument = instrument;
        this.api = api;
        this.repository = repository;
        this.marketDataStreamStub = marketDataStreamStub;
        this.tgService = tgService;
        this.accountIdLong = accountIdLong;
        this.accountIdShort = accountIdShort;

        this.atrMultiplier = instrument.atrMultiplier();
        this.currentQuantity = instrument.quantity();

        // Внутри конструктора HedgeBot.java
        tgService.addCallbackHandler(command -> {
            String targetPrefix = "FORCE_CLOSE_" + instrument.ticker();

            if (command.equals(targetPrefix)) {
                if (status == BotStatus.TRAILING) {
                    logger.warn("[{}] Получен сигнал финализации из Telegram!", instrument.ticker());
                    tgService.sendNotification(String.format("🛠 *[%s]* Ручная финализация подтверждена. Выставляю ордер...", instrument.ticker()));

                    String activeAccountId = isLongActive ? accountIdLong : accountIdShort;
                    OrderDirection dir = isLongActive ? OrderDirection.ORDER_DIRECTION_SELL : OrderDirection.ORDER_DIRECTION_BUY;

                    // Запуск штатного механизма закрытия
                    finalizeCycle(activeAccountId, dir, lastClosedPrice);
                } else {
                    tgService.sendNotification(String.format("⚠ *[%s]* Запрос отклонен: бот находится вне фазы Трейлинга.", instrument.ticker()));
                }
            }
        });
    }

    public String getAccountIdLong() {
        return accountIdLong;
    }

    public String getAccountIdShort() {
        return accountIdShort;
    }

    public void openHedge() {
        logger.info("[{}] Отправка одновременных заявок...", instrument.ticker());

        CompletableFuture<PostOrderResponse> taskLong = CompletableFuture.supplyAsync(() ->
                api.postOrder(accountIdLong, instrument.figi(), currentQuantity, OrderDirection.ORDER_DIRECTION_BUY)
        );

        CompletableFuture<PostOrderResponse> taskShort = CompletableFuture.supplyAsync(() ->
                api.postOrder(accountIdShort, instrument.figi(), currentQuantity, OrderDirection.ORDER_DIRECTION_SELL)
        );

        try {
            CompletableFuture.allOf(taskLong, taskShort).join();

            // Эталонная цена для проверки (середина канала)
            double midPrice = (resistanceLevel + supportLevel) / 2;

            // Вместо sanitizePrice используем расчет цены за штуку
            this.longEntryPrice = calculatePricePerShare(
                    taskLong.get().getInitialOrderPrice(), // или ExecutedOrderPrice
                    instrument.quantity(),
                    instrument.lotSize()
            );
            this.shortEntryPrice = calculatePricePerShare(
                    taskShort.get().getInitialOrderPrice(),
                    instrument.quantity(),
                    instrument.lotSize()
            );

            logger.info("[{}] The lock is open! LONG: {} | SHORT: {}",
                    instrument.ticker(),
                    String.format("%.2f", longEntryPrice),
                    String.format("%.2f", shortEntryPrice));

            this.isLocked = true;
            this.isLongActive = false;
            this.isShortActive = false;
            this.status = BotStatus.LOCKED;

        } catch (Exception e) {
            logger.error("[{}] Критическая ошибка при открытии замка: {}", instrument.ticker(), e.getMessage());
            this.status = BotStatus.PAUSE;
        }
        saveState();
    }

    // Вспомогательный метод для фильтрации глюков API
    private double sanitizePrice(MoneyValue mv, double referencePrice) {
        double apiPrice = moneyToDouble(mv);

        // Если отклонение от эталона более чем в 2 раза (или 50% - на твой вкус)
        if (referencePrice > 0 && (apiPrice > referencePrice * 1.5 || apiPrice < referencePrice * 0.5)) {

            // Проверяем гипотезу "ошибки в 100 раз" (баг Sandbox)
            double correctedPrice = apiPrice / 100.0;
            if (Math.abs(correctedPrice - referencePrice) < Math.abs(apiPrice - referencePrice)) {
                logger.error("[{}] ⚠ ГЛЮК РАЗРЯДНОСТИ API! Получено: {}, Ожидалось около: {}. Делю на 100.",
                        instrument.ticker(), apiPrice, referencePrice);
                return correctedPrice;
            }

            logger.error("[{}] ⚠ АНОМАЛЬНАЯ ЦЕНА API! Получено: {}, Ожидалось около: {}. Использую эталон.",
                    instrument.ticker(), apiPrice, referencePrice);
            return referencePrice;
        }
        return apiPrice;
    }

    /**
     * Конвертирует MoneyValue в double для удобного вывода в лог.
     */
    private double moneyToDouble(MoneyValue m) {
        if (m == null) return 0.0;
        return m.getUnits() + m.getNano() / 1_000_000_000.0;
    }

    private double calculatePricePerShare(MoneyValue totalAmountMV, long lots, int lotSize) {
        double totalAmount = moneyToDouble(totalAmountMV);
        long totalShares = lots * lotSize;
        if (totalShares <= 0) return 0.0;
        return totalAmount / totalShares;
    }

    /**
     * Подписывается на асинхронный стрим рыночных данных для получения 5-минутных свечей.
     * При получении новой свечи данные передаются в метод {@link #processCandle(Candle)}.
     *
     */
    public void subscribeCandles() {
        // 1. Если старый стрим еще "жив", пробуем его аккуратно закрыть
        if (currentRequestObserver != null) {
            try {
                currentRequestObserver.onCompleted();
            } catch (Exception ignored) {}
        }

        StreamObserver<MarketDataResponse> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(MarketDataResponse response) {
                if (response.hasCandle()) {
                    processCandle(response.getCandle());
                }
            }

            @Override
            public void onError(Throwable t) {
                // Ошибка EOS (End of Stream) попадает сюда
                logger.error("[{}] Ошибка стрима: {}. Реконнект через 5 сек...", instrument.ticker(), t.getMessage());
                CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS).execute(() -> subscribeCandles());
            }

            @Override
            public void onCompleted() {
                logger.warn("[{}] Стрим завершен сервером. Реконнект...", instrument.ticker());
                subscribeCandles();
            }
        };

        // 2. Сохраняем новый обзервер в поле класса
        this.currentRequestObserver = marketDataStreamStub.marketDataStream(responseObserver);

        // 3. Отправляем подписку
        this.currentRequestObserver.onNext(MarketDataRequest.newBuilder()
                .setSubscribeCandlesRequest(SubscribeCandlesRequest.newBuilder()
                        .setSubscriptionAction(SubscriptionAction.SUBSCRIPTION_ACTION_SUBSCRIBE)
                        .addInstruments(CandleInstrument.newBuilder()
                                .setFigi(instrument.figi())
                                .setInterval(SubscriptionInterval.SUBSCRIPTION_INTERVAL_FIVE_MINUTES)
                                .build())
                        .build())
                .build());
    }

    /**
     * Основная логика принятия решений на основе закрытия свечи.
     * 1. В режиме "замок": проверяет пробой уровней для закрытия убыточной ноги.
     * 2. В режиме "тренд": сопровождает прибыльную ногу с помощью Trailing Stop на базе ATR.
     * 3. При срабатывании стопа инициирует асинхронный перезапуск цикла.
     *
     * @param candle Объект закрытой свечи, полученный из стрима
     */
    synchronized void processCandle(Candle candle) {
        // --- СТАРЫЙ МЕТОД: МГНОВЕННЫЙ РАСЧЕТ PPS ---
        long now = System.currentTimeMillis();
        if (lastPushTimestamp != 0) {
            long diff = now - lastPushTimestamp;
            if (diff > 0) {
                double currentPps = 1000.0 / diff; // Частота на основе одного интервала
                // EMA для сглаживания (чтобы цифры в логе можно было прочитать)
                pushesPerSecond = (currentPps * 0.1) + (pushesPerSecond * 0.9);
            }
        }
        lastPushTimestamp = now;
        // ------------------------------------------

        if (status == BotStatus.PAUSE || status == BotStatus.INITIALIZING) return;

        double closePrice = candleToDouble(candle.getClose());

        // 1. Сначала выполняем логику
        if (status == BotStatus.LOCKED) {
            checkAndBreakHedge(closePrice);
        } else if (status == BotStatus.TRAILING) {
            handleTrailingStop(closePrice);
        }

        // 2. И только в самом конце ОДИН раз рисуем лог
        logCurrentStatus(closePrice);
    }

    private void logCurrentStatus(double closePrice) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastLogTime < 10_000) return;

        // 1. РЕЖИМ ЗАМКА (LOCKED)
        if (status == BotStatus.LOCKED) {
            String bar = getProgressBar(supportLevel, resistanceLevel, closePrice, '-');
            logger.info("[{} {} {} {}] {} % | Freq : {}",
                    instrument.ticker(),
                    String.format("%.2f", supportLevel),
                    bar,
                    String.format("%.2f", resistanceLevel),
                    String.format("%.1f", (resistanceLevel / supportLevel - 1) * 100),
                    String.format("%.1f", pushesPerSecond));

            lastLogTime = currentTime; // Обновляем таймер только при выводе
            return;
        }

        // 2. РЕЖИМ ТРЕЙЛИНГА (TRAILING)
        if (status == BotStatus.TRAILING) {
            double offset = lastAtr * instrument.atrMultiplier();
            double moveTrigger;

            if (isLongActive) {
                moveTrigger = trailingStopPrice + offset;
                String bar = getProgressBar(trailingStopPrice, moveTrigger, closePrice, '>');
                logger.info("[{} {} {} {}] Enter: {} Exit: {} | Freq : {}",
                        instrument.ticker(),
                        String.format("%.2f", trailingStopPrice),
                        bar,
                        String.format("%.2f", moveTrigger),
                        String.format("%.2f", longEntryPrice),
                        String.format("%.2f", lastClosedPrice),
                        String.format("%.1f", pushesPerSecond));
            } else if (isShortActive) {
                moveTrigger = trailingStopPrice - offset;
                String bar = getProgressBar(moveTrigger, trailingStopPrice, closePrice, '<');
                logger.info("[{} {} {} {}] Enter: {} Exit: {} | Freq : {}",
                        instrument.ticker(),
                        String.format("%.2f", moveTrigger),
                        bar,
                        String.format("%.2f", trailingStopPrice),
                        String.format("%.2f", shortEntryPrice),
                        String.format("%.2f", lastClosedPrice),
                        String.format("%.1f", pushesPerSecond));
            }

            lastLogTime = currentTime;
        }
    }

    private void checkAndBreakHedge(double closePrice) {
        // --- УСЛОВИЕ ПРОБОЯ ВВЕРХ ---
        if (closePrice > resistanceLevel) {
            // Если это первый пуш ИЛИ цена растет/стоит на месте
            if (breakoutPushCount == 0 || closePrice >= lastBreakoutPrice) {
                breakoutPushCount++;
            } else {
                // Откат за уровнем — сбрасываем счетчик к 1
                breakoutPushCount = 1;
//                logger.info("[{}] ↩ Откат вверх ({} < {}). Reset counter to 1.",
//                        instrument.ticker(), closePrice, lastBreakoutPrice);
            }

            lastBreakoutPrice = closePrice;
            if (breakoutPushCount >= instrument.badPushThreshold() / 2) {
                logger.info("[{}] [{}                            {}] Push: {} ({}) {} % | Freq : {}",
                        instrument.ticker(),
                        String.format("%.2f",supportLevel),
                        String.format("%.2f",resistanceLevel), breakoutPushCount,
                        String.format("%.2f",closePrice),
                        String.format("%.1f", (resistanceLevel / supportLevel - 1) * 100),
                        String.format("%.1f", pushesPerSecond));
            }

            if (breakoutPushCount >= instrument.badPushThreshold()) {
                isLocked = false;
                isLongActive = true;
                logger.warn("[{}] ⬆ BREAKOUT UP (Pushes: {})! Closing SHORT. [{}                {}] Price: {}",
                        instrument.ticker(), breakoutPushCount,
                        String.format("%.2f", supportLevel),
                        String.format("%.2f", resistanceLevel), closePrice);
                executeHedgeBreak(accountIdShort, OrderDirection.ORDER_DIRECTION_BUY, closePrice, true);
                resetBreakoutCounter();
            }
            return;
        }

        // --- УСЛОВИЕ ПРОБОЯ ВНИЗ ---
        else if (closePrice < supportLevel) {
            // Если это первый пуш ИЛИ цена падает/стоит на месте (для шорта)
            if (breakoutPushCount == 0 || closePrice <= lastBreakoutPrice) {
                breakoutPushCount++;
            } else {
                // Откат (рост) за уровнем — сбрасываем счетчик к 1
                breakoutPushCount = 1;
//                logger.info("[{}] ↩ Откат вниз ({} > {}). Reset counter to 1.",
//                        instrument.ticker(), closePrice, lastBreakoutPrice);
            }

            lastBreakoutPrice = closePrice;
            if (breakoutPushCount >= instrument.badPushThreshold() / 2) {
                logger.info("[{}] Push: {} ({}) [{}                            {}] {} % | Freq : {}",
                        instrument.ticker(), breakoutPushCount, String.format("%.2f", closePrice),
                        String.format("%.2f", supportLevel),
                        String.format("%.2f", resistanceLevel),
                        String.format("%.1f", (resistanceLevel / supportLevel - 1) * 100),
                        String.format("%.1f", pushesPerSecond));
            }

            if (breakoutPushCount >= instrument.badPushThreshold()) {
                isLocked = false;
                isShortActive = true;
                logger.warn("[{}] ⬇ BREAKOUT DOWN (Pushes: {})! Closing LONG. Price: {} [{}                {}]",
                        instrument.ticker(), breakoutPushCount, closePrice,
                        String.format("%.2f", supportLevel),
                        String.format("%.2f", resistanceLevel));
                executeHedgeBreak(accountIdLong, OrderDirection.ORDER_DIRECTION_SELL, closePrice, false);
                resetBreakoutCounter();
            }
            return;
        }

        // --- ЕСЛИ ЦЕНА ВЕРНУЛАСЬ В КАНАЛ ---
        if (breakoutPushCount > 0) {
//            logger.info("[{}] ↩ Возврат в канал (Цена: {}). Счетчик сброшен.",
//                    instrument.ticker(), closePrice);
            resetBreakoutCounter();
        }
    }

    private void resetBreakoutCounter() {
        breakoutPushCount = 0;
        lastBreakoutPrice = 0;
    }

    private void executeHedgeBreak(String accountId, OrderDirection direction, double closePrice, boolean isUp) {
        try {
            long realShares = api.getRealQuantity(accountId, instrument.figi());

            if (realShares <= 0) {
                logger.error("[{}] Ошибка вскрытия: Позиция не найдена!", instrument.ticker());
                return;
            }

            long lotsToClose = realShares / instrument.lotSize();

            if (lotsToClose == 0) {
                logger.warn("[{}] Недостаточно акций для закрытия даже 1 лота (всего {} шт)",
                        instrument.ticker(), realShares);
                return;
            }

            var response = api.closePosition(accountId, instrument.figi(), lotsToClose, direction);

            // 1. Рассчитываем цену одной акции из ответа API
            double apiPricePerShare = calculatePricePerShare(
                    response.getExecutedOrderPrice(),
                    lotsToClose,
                    instrument.lotSize()
            );

            // 2. СТРАХОВКА ОТ ЗАВИСАНИЯ SANDBOX: Если API вернуло 0.00, берем цену пробойной свечи
            if (apiPricePerShare <= 0) {
                logger.warn("[{}] API песочницы вернуло 0.00. Применяю аварийную страховку (цена свечи пробоя): {}",
                        instrument.ticker(), closePrice);
                this.lastClosedPrice = closePrice;
            } else {
                this.lastClosedPrice = apiPricePerShare;
            }

            this.status = BotStatus.TRAILING;
            this.isLocked = false;

            // 3. ОТПРАВКА ИНТЕРАКТИВНОГО АЛЕРТА С КНОПКОЙ РУЧНОГО ЗАКРЫТИЯ
            String emoji = isUp ? "🚀" : "🩸";
            String mode = isUp ? "LONG" : "SHORT";
            String message = String.format(
                    "%s *[%s]* *ВСКРЫТИЕ ЗАМКА!*\n" +
                            "• Направление: *%s*\n" +
                            "• Цена вскрытия: `%s`\n" +
                            "• Частота потока (Freq): `%.1f Гц`",
                    emoji,
                    instrument.ticker(),
                    mode,
                    String.format("%.2f", lastClosedPrice),
                    pushesPerSecond
            );

            tgService.sendBreakoutAlert(instrument.ticker(), message);

        } catch (Exception e) {
            logger.error("[{}] КРИТИЧЕСКАЯ ОШИБКА ВСКРЫТИЯ! {}", instrument.ticker(), e.getMessage());
            return;
        }

        // --- 4. РАСЧЕТ НАЧАЛЬНОГО СТОПА (На основе ПРАВИЛЬНОЙ lastClosedPrice) ---
        double offset = lastAtr * instrument.atrMultiplier();
        double breakevenOffset = lastAtr * 0.2;

        if (isUp) {
            double initialStop = closePrice - offset;
            double safeBreakeven = lastClosedPrice - breakevenOffset;
            this.trailingStopPrice = (initialStop < safeBreakeven && closePrice >= lastClosedPrice) ? safeBreakeven : initialStop;
            this.isLongActive = true;
            this.isShortActive = false;
        } else {
            double initialStop = closePrice + offset;
            double safeBreakeven = lastClosedPrice + breakevenOffset;
            this.trailingStopPrice = (initialStop > safeBreakeven && closePrice <= lastClosedPrice) ? safeBreakeven : initialStop;
            this.isShortActive = true;
            this.isLongActive = false;
        }

        logger.info("[{}] TRAILING activated: {} (Last closed: {})",
                instrument.ticker(), String.format("%.2f", trailingStopPrice), String.format("%.2f", lastClosedPrice));
        saveState();
    }

    private void handleTrailingStop(double closePrice) {
        double offset = lastAtr * instrument.atrMultiplier();
        double breakevenOffset = lastAtr * 0.2;
        double commissionOffset = lastClosedPrice * instrument.commission() * 2 / 100;

        // Порог аварийного выхода: 0.06% от цены вскрытия
        double emergencyThreshold = lastClosedPrice * 0.0006;

        if (isLongActive) {
            double potentialStop = closePrice - offset;

            // 1. Мягкий безубыток
            double safeBreakeven = lastClosedPrice - breakevenOffset;
            if (potentialStop < safeBreakeven && closePrice > lastClosedPrice) {
                potentialStop = safeBreakeven;
            }

            // 2. Силовой перенос
            if (closePrice > resistanceLevel && potentialStop < resistanceLevel) {
                potentialStop = resistanceLevel;
            }

            // 3. ЧЕСТНЫЙ БЕЗУБЫТОК
            double trueBreakeven = lastClosedPrice + commissionOffset;
            if (closePrice > trueBreakeven && potentialStop < trueBreakeven) {
                potentialStop = trueBreakeven;
                if (potentialStop > trailingStopPrice) {
                    logger.info("[{}] 🛡 TRUE BREAKEVEN! Стоп защищает комиссию: {}",
                            instrument.ticker(), String.format("%.2f", trueBreakeven));
                }
            }

            // ПОДТЯЖКА СТОПА
            if (potentialStop > trailingStopPrice) {
                trailingStopPrice = potentialStop;
                logger.info("[{}] ⬆ The stop moved up: {} (Price: {})",
                        instrument.ticker(), String.format("%.2f", trailingStopPrice), String.format("%.2f", closePrice));
                saveState();
            }

            // ПРОВЕРКА ВЫХОДА (Счетчик пушей) + 0.06%
            double currentLossInRub = lastClosedPrice - closePrice;
            if (currentLossInRub >= emergencyThreshold) {
                tgService.sendNotification(String.format("🚨 *[%s] EMERGENCY STOP!*\nУбыток превысил порог 0.06%% (%.2f rub).\nПозиция закрыта по цене: *%.2f*",
                        instrument.ticker(), currentLossInRub, closePrice));
                logger.warn("[{}] 🚨 EMERGENCY STOP: Loss reached 0.06% ({} rub). Closing LONG. Exit: {}",
                        instrument.ticker(), String.format("%.2f", currentLossInRub), String.format("%.2f", closePrice));
                finalizeCycle(accountIdLong, OrderDirection.ORDER_DIRECTION_SELL, closePrice);
                stopViolationCount = 0;
            } else if (closePrice <= trailingStopPrice) {
                stopViolationCount++;
                if (stopViolationCount >= instrument.stopPushThreshold()) {
                    logger.warn("[{}] 🛑 STOP TRIGGERED: {} ticks beyond stop: {}. Closing LONG. Exit: {}",
                            instrument.ticker(), stopViolationCount,
                            String.format("%.2f", trailingStopPrice),
                            String.format("%.2f", closePrice));
                    finalizeCycle(accountIdLong, OrderDirection.ORDER_DIRECTION_SELL, closePrice);
                    stopViolationCount = 0;
                }
            } else {
                stopViolationCount = 0; // Возврат в безопасную зону — мгновенный сброс
            }

        } else if (isShortActive) {
            double potentialStop = closePrice + offset;

            // 1. Мягкий безубыток
            double safeBreakeven = lastClosedPrice + breakevenOffset;
            if (potentialStop > safeBreakeven && closePrice < lastClosedPrice) {
                potentialStop = safeBreakeven;
            }

            // 2. Силовой перенос
            if (closePrice < supportLevel && potentialStop > supportLevel) {
                potentialStop = supportLevel;
            }

            // 3. ЧЕСТНЫЙ БЕЗУБЫТОК
            double trueBreakeven = lastClosedPrice - commissionOffset;
            if (closePrice < trueBreakeven && potentialStop > trueBreakeven) {
                potentialStop = trueBreakeven;
                if (potentialStop < trailingStopPrice) {
                    logger.info("[{}] 🛡 TRUE BREAKEVEN! Стоп защищает комиссию: {}",
                            instrument.ticker(), String.format("%.2f", trueBreakeven));
                }
            }

            // ПОДТЯЖКА СТОПА
            if (potentialStop < trailingStopPrice) {
                trailingStopPrice = potentialStop;
                logger.info("[{}] ⬇ The stop moved down: {} (Price: {})",
                        instrument.ticker(), String.format("%.2f", trailingStopPrice), String.format("%.2f", closePrice));
                saveState();
            }

            // ПРОВЕРКА ВЫХОДА (Счетчик пушей) + 0.06%
            double currentLossInRub = closePrice - lastClosedPrice;
            if (currentLossInRub >= emergencyThreshold) {
                // В блоках emergencyThreshold
                tgService.sendNotification(String.format("🚨 *[%s] EMERGENCY STOP!*\nУбыток превысил порог 0.06%% (%.2f rub).\nПозиция закрыта по цене: *%.2f*",
                        instrument.ticker(), currentLossInRub, closePrice));
                logger.warn("[{}] 🚨 EMERGENCY STOP: Loss reached 0.06% ({} rub). Closing SHORT. Exit: {}",
                        instrument.ticker(), String.format("%.2f", currentLossInRub), String.format("%.2f", closePrice));
                finalizeCycle(accountIdShort, OrderDirection.ORDER_DIRECTION_BUY, closePrice);
                stopViolationCount = 0;
            } else if (closePrice >= trailingStopPrice) {
                stopViolationCount++;
                if (stopViolationCount >= instrument.stopPushThreshold()) {
                    logger.warn("[{}] 🛑 STOP TRIGGERED: {} ticks beyond {}. Closing SHORT.",
                            instrument.ticker(), stopViolationCount, String.format("%.2f", trailingStopPrice));
                    finalizeCycle(accountIdShort, OrderDirection.ORDER_DIRECTION_BUY, closePrice);
                    stopViolationCount = 0;
                }
            } else {
                stopViolationCount = 0; // Сброс
            }
        }
    }

    private void finalizeCycle(String accountId, OrderDirection direction, double currentPrice) {
        boolean wasLong = isLongActive;
        String mode = wasLong ? "LONG" : "SHORT";

        double exitPrice;
        long realShares;
        long lotsToClose;

        try {
            // 0. Уточняем реальный объем в ШТУКАХ (акциях)
            realShares = api.getRealQuantity(accountId, instrument.figi());

            if (realShares == -1) return;
            if (realShares == 0) {
                logger.warn("[{}] Позиция уже закрыта на бирже.", instrument.ticker());
                this.status = BotStatus.PAUSE;
                CompletableFuture.runAsync(this::resetCycle);
                return;
            }

            // 1. КОНВЕРТИРУЕМ В ЛОТЫ ДЛЯ API
            lotsToClose = realShares / instrument.lotSize();

            if (lotsToClose == 0) {
                logger.error("[{}] Критическая ошибка: остаток {} акций меньше 1 лота!",
                        instrument.ticker(), realShares);
                return;
            }

            // 2. ОТПРАВЛЯЕМ ЛОТЫ НА ЗАКРЫТИЕ
            var response = api.closePosition(accountId, instrument.figi(), lotsToClose, direction);
            exitPrice = calculatePricePerShare(
                    response.getExecutedOrderPrice(),
                    lotsToClose,
                    instrument.lotSize()
            );

            // 3. Смена статусов (только при успехе API)
            this.status = BotStatus.PAUSE;
            this.isLongActive = false;
            this.isShortActive = false;

            logger.warn("[{}] !!! ТРЕЙЛИНГ-СТОП ({}) СРАБОТАЛ !!! Stop: {} ",
                    instrument.ticker(), mode, String.format("%.2f", trailingStopPrice));
            logger.info("[{}] Закрыто лотов: {} ({} акций) | Close price: {}",
                    instrument.ticker(), lotsToClose, realShares, String.format("%.2f", exitPrice));

        } catch (Exception e) {
            logger.error("[{}] Ошибка финализации: {}. Ждем 5 секунд...", instrument.ticker(), e.getMessage());
            try { Thread.sleep(5000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            return;
        }

        // --- 4. РАСЧЕТ ПРОФИТА С УЧЕТОМ КОМИССИЙ (0.05% за сделку) ---

        // 4.1. Грязная прибыль (движение цены)
        double rawProfit = (wasLong
                ? (exitPrice - longEntryPrice) + (shortEntryPrice - lastClosedPrice)
                : (shortEntryPrice - exitPrice) + (lastClosedPrice - longEntryPrice)) * realShares;

        // 4.2. Суммарный оборот по всем 4 операциям
        // (Вход Long + Вход Short + Вскрытие + Финал) * Кол-во акций
        double totalTurnover = (longEntryPrice + shortEntryPrice + lastClosedPrice + exitPrice) * realShares;

        // 4.3. Комиссия (0.05% от оборота)
        double totalFees = totalTurnover * instrument.commission() / 100;

        // 4.4. Чистая прибыль за круг
        double cycleProfit = rawProfit - totalFees;

        totalProfit += cycleProfit;

        String profitEmoji = cycleProfit >= 0 ? "💰" : "📉";
        tgService.sendNotification(String.format("%s *[%s]* Цикл завершен!\nЧистый профит за круг: *%.2f руб.*\nОбщий профит: *%.2f руб.*",
                profitEmoji, instrument.ticker(), cycleProfit, totalProfit));

        // Логируем "налог" брокера для прозрачности
        logger.info("[{}] Cycle fees: {} rub. (Gross profit: {} rub.)",
                instrument.ticker(), String.format("%.2f", totalFees), String.format("%.2f", rawProfit));

        printCycleResults(cycleProfit);

        // 5. Перезапуск
        CompletableFuture.runAsync(this::resetCycle);
        saveState();
    }

    private void printCycleResults(double cycleProfit) {
        logger.info("---------------------------------------");
        logger.info("[{}] END OF CIRCLE. Profit: {} rub.", instrument.ticker(), String.format("%.2f", cycleProfit));
        logger.info("[{}] TOTAL: {} rub.", instrument.ticker(), String.format("%.2f", totalProfit));
        logger.info("---------------------------------------");
    }

    /**
     * Выполняет полный сброс состояния робота и подготовку к новому циклу.
     * Включает минутную паузу, перерасчет уровней и открытие нового "замка".
     * Запускается асинхронно, чтобы не блокировать поток рыночных данных.
     */
    private void resetCycle() {
        this.status = BotStatus.PAUSE; // Блокируем входящие свечи на время паузы

        this.isLongActive = false;
        this.isShortActive = false;
        this.trailingStopPrice = 0;
        this.longEntryPrice = 0;
        this.shortEntryPrice = 0;
        this.lastClosedPrice = 0;
        this.supportLevel = 0;
        this.resistanceLevel = 0;
        this.isLocked = false; // Важно сбросить, чтобы initLevels сработал

        saveState();

        try {
            logger.info("[{}] Pause (30 sec)...", instrument.ticker());
            Thread.sleep(30_000);

            this.status = BotStatus.INITIALIZING; // Режим подготовки

            initLevels(instrument.figi());
            openHedge();

            this.isLocked = true;
            this.status = BotStatus.LOCKED; // ОТКРЫВАЕМ шлюз для обработки свечей!

            saveState();
            logger.info("--- [{}] НОВЫЙ ЦИКЛ ЗАПУЩЕН ---", instrument.ticker());
        } catch (InterruptedException e) {
            logger.error("[{}] Критическая ошибка при паузе цикла", instrument.ticker(), e);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Вспомогательный метод для конвертации дробных чисел из формата Protobuf Quotation в Double.
     *
     * @param q Объект Quotation (units + nanos)
     * @return Числовое представление в формате double
     */
    private double candleToDouble(Quotation q) {
        return q.getUnits() + q.getNano() / 1_000_000_000.0;
    }

    /**
     * Рассчитывает уровни поддержки и сопротивления на основе истории торгов.
     * Использует High и Low последних 4-х часовых свечей для определения границ "замка".
     * В конце вызывает обновление ATR для актуализации волатильности.
     *
     * @param figi Идентификатор финансового инструмента (FIGI)
     */
    public void initLevels(String figi) {
        if (this.supportLevel != 0 && this.resistanceLevel != 0) {
            logger.info("[{}] The levels have gotten from the state file: {} - {}",
                    instrument.ticker(), String.format("%.2f", supportLevel), String.format("%.2f", resistanceLevel));
            updateAtr(figi);
            return;
        }

        Instant now = Instant.now();
        Instant from = now.minus(60, ChronoUnit.MINUTES);

        Timestamp toTs = Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build();
        Timestamp fromTs = Timestamp.newBuilder().setSeconds(from.getEpochSecond()).build();

        GetCandlesResponse response = api.getCandles(figi, fromTs, toTs);
        List<HistoricCandle> candles = response.getCandlesList();

        if (candles.isEmpty()) {
            logger.error("[{}] Can not get candles!", instrument.ticker());
            return;
        }

        double max = 0;
        double min = Double.MAX_VALUE;
        for (HistoricCandle candle : candles) {
            double high = candleToDouble(candle.getHigh());
            double low = candleToDouble(candle.getLow());
            if (high > max) max = high;
            if (low < min) min = low;
        }

        // --- ЛОГИКА СЖАТИЯ ---
        double initialWidth = max - min;
        double compressionOffset = initialWidth * instrument.rangeCompression();

        this.resistanceLevel = max - compressionOffset;
        this.supportLevel = min + compressionOffset;
        // ---------------------

        updateAtr(figi);

        logger.info("[{}] Уровни установлены строго по теням свечей: [ {} - {} ]",
                instrument.ticker(),
                String.format("%.2f", supportLevel),
                String.format("%.2f", resistanceLevel));
        saveState();
    }

    private void updateAtr(String figi) {
        Instant now = Instant.now();
        Instant from = now.minus(1, ChronoUnit.HOURS);

        Timestamp toTs = Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build();
        Timestamp fromTs = Timestamp.newBuilder().setSeconds(from.getEpochSecond()).build();

        try {
            GetCandlesResponse response = api.getCandles(figi, fromTs, toTs);
            int candlesCount = response.getCandlesCount();

            if (candlesCount == 0) {
                logger.warn("[{}] Не удалось получить свечи для ATR. Используется старое значение: {}",
                        instrument.ticker(), String.format("%.2f", lastAtr));
                return;
            }

            double totalRange = 0;
            for (HistoricCandle candle : response.getCandlesList()) {
                totalRange += (candleToDouble(candle.getHigh()) - candleToDouble(candle.getLow()));
            }

            // Обновляем значение волатильности
            this.lastAtr = totalRange / candlesCount;
            logger.info("[{}] ATR обновлен: {} (на основе {} свечей)",
                    instrument.ticker(), String.format("%.2f", lastAtr), candlesCount);
            saveState();

        } catch (Exception e) {
            logger.error("[{}] Ошибка при обновлении ATR. Оставлено: {}",
                    instrument.ticker(), String.format("%.2f", lastAtr), e);
        }
    }

    public boolean tryAttachToExistingHedge() {
        try {
            // Используем TinkoffApiService для получения портфелей
            var portfolioLong = api.getPortfolio(accountIdLong);
            var portfolioShort = api.getPortfolio(accountIdShort);

            var posLong = portfolioLong.getPositionsList().stream()
                    .filter(p -> p.getFigi().equals(instrument.figi()) && candleToDouble(p.getQuantity()) > 0)
                    .findFirst();

            var posShort = portfolioShort.getPositionsList().stream()
                    .filter(p -> p.getFigi().equals(instrument.figi()) && candleToDouble(p.getQuantity()) < 0)
                    .findFirst();

            // Сценарий 1: Полный замок
            if (posLong.isPresent() && posShort.isPresent()) {
//                this.currentQuantity = (long) Math.abs(candleToDouble(posLong.get().getQuantity()));
                double totalShares = Math.abs(candleToDouble(posLong.get().getQuantity()));
                long detectedLots = (long) (totalShares / instrument.lotSize());
                this.longEntryPrice = moneyToDouble(posLong.get().getAveragePositionPrice());
                this.shortEntryPrice = moneyToDouble(posShort.get().getAveragePositionPrice());

                this.isLocked = true;
                this.isLongActive = false;
                this.isShortActive = false;
                this.status = BotStatus.LOCKED;

                logger.info("[{}] >>> ПОДХВАЧЕН ПОЛНЫЙ ЗАМОК: {} лотов ({} шт), Вход L: {}, S: {}",
                        instrument.ticker(), detectedLots, totalShares, longEntryPrice, shortEntryPrice);
                saveState();
                return true;
            }

            // Сценарий 2: Активный Long
            else if (posLong.isPresent()) {
//                this.currentQuantity = (long) Math.abs(candleToDouble(posLong.get().getQuantity()));
                double totalShares = Math.abs(candleToDouble(posLong.get().getQuantity()));
                long detectedLots = (long) (totalShares / instrument.lotSize());
                this.longEntryPrice = moneyToDouble(posLong.get().getAveragePositionPrice());

                this.isLocked = false;
                this.isLongActive = true;
                this.isShortActive = false;
                this.status = BotStatus.TRAILING;

                double currentPrice = moneyToDouble(posLong.get().getCurrentPrice());
                this.trailingStopPrice = currentPrice - (lastAtr * atrMultiplier);

                logger.info("[{}] >>> ПОДХВАЧЕН АКТИВНЫЙ LONG: {} лотов ({} шт), Enter: {}, Stop: {}",
                        instrument.ticker(), detectedLots, totalShares, longEntryPrice, String.format("%.2f", trailingStopPrice));
                saveState();
                return true;
            }

            // Сценарий 3: Активный Short
            else if (posShort.isPresent()) {
//                this.currentQuantity = (long) Math.abs(candleToDouble(posShort.get().getQuantity()));
                double totalShares = Math.abs(candleToDouble(posShort.get().getQuantity()));
                long detectedLots = (long) (totalShares / instrument.lotSize());
                this.shortEntryPrice = moneyToDouble(posShort.get().getAveragePositionPrice());

                this.isLocked = false;
                this.isLongActive = false;
                this.isShortActive = true;
                this.status = BotStatus.TRAILING;

                double currentPrice = moneyToDouble(posShort.get().getCurrentPrice());
                this.trailingStopPrice = currentPrice + (lastAtr * atrMultiplier);

                logger.info("[{}] >>> ПОДХВАЧЕН АКТИВНЫЙ SHORT: {} лотов ({} шт), Enter: {}, Stop: {}",
                        instrument.ticker(), detectedLots, totalShares, shortEntryPrice, String.format("%.2f", trailingStopPrice));
                saveState();
                return true;
            }

        } catch (Exception e) {
            logger.error("[{}] Ошибка при восстановлении состояния позиций: {}", instrument.ticker(), e.getMessage());
        }
        return false;
    }

    /**
     * Выводит в консоль список всех доступных реальных счетов.
     * Используйте этот метод один раз, чтобы узнать ID для вставки в main.
     */
    public void printRealAccounts() {
        try {
            // Вызываем через сервис
            var response = api.getAccounts();
            for (Account acc : response.getAccountsList()) {
                logger.info("[USER] Счет: {} | ID: {} | Статус: {}",
                        acc.getName(), acc.getId(), acc.getStatus());
            }
        } catch (Exception e) {
            logger.error("Ошибка при получении списка реальных счетов: {}", e.getMessage());
        }
    }

    /**
     * Подготавливает счета для работы в песочнице.
     * Проверяет наличие счетов, а также баланс на них.
     */
    public void prepareSandboxAccounts() {
        // Пополняем баланс через сервис
        api.ensureSandboxBalance(accountIdLong, 50_000);
        api.ensureSandboxBalance(accountIdShort, 50_000);
    }


    /**
     * Списывает все свободные денежные средства со счета в песочнице,
     * приводя рублевый баланс к нулю.
     *
     * @param accountId ID счета для обнуления
     */
    public void resetBalanceToZero(String accountId) {
        api.resetSandboxBalance(accountId);
    }


    /**
     * Выводит в лог текущее состояние портфеля по указанному счету.
     * Показывает список ценных бумаг, их количество и валютные остатки.
     *
     * @param accountId ID счета для анализа
     */
    public void printPortfolio(String accountId) {
        logger.info("=== ПОРТФЕЛЬ СЧЕТА: {} ===================================", accountId);
        try {
            // Используем сервис
            var portfolio = api.getPortfolio(accountId);

            if (portfolio.getPositionsList().isEmpty()) {
                logger.info("Бумаги в портфеле отсутствуют.");
            } else {
                for (var position : portfolio.getPositionsList()) {
                    double quantity = candleToDouble(position.getQuantity());
                    logger.info("FIGI: {} | Кол-во: {} | Цена: {} {}",
                            position.getFigi(),
                            quantity,
                            moneyToDouble(position.getCurrentPrice()),
                            position.getCurrentPrice().getCurrency());
                }
            }

            var cash = portfolio.getTotalAmountCurrencies();
            logger.info("Свободные средства: {} {}",
                    moneyToDouble(cash),
                    cash.getCurrency());

            logger.info("==========================================================================");
        } catch (Exception e) {
            logger.error("[{}] Ошибка при печати портфеля: {}", instrument.ticker(), e.getMessage());
        }
    }

    /**
     * Выводит в лог состояние портфеля только для текущего инструмента бота.
     *
     * @param accountId ID счета для анализа
     */
    public void printPortfolioByFigi(String accountId) {
        try {
            // Используем декомпозированный сервис
            var portfolio = api.getPortfolio(accountId);

            var positionOpt = portfolio.getPositionsList().stream()
                    .filter(p -> p.getFigi().equals(instrument.figi()))
                    .findFirst();

            if (positionOpt.isPresent()) {
                var pos = positionOpt.get();
                double quantity = candleToDouble(pos.getQuantity());
                double currentPrice = moneyToDouble(pos.getCurrentPrice());
                double averagePrice = moneyToDouble(pos.getAveragePositionPrice());

                double pnl = (currentPrice - averagePrice) * quantity;

                logger.info("[{}] Кол-во: {} | Вход: {} | Тек: {} | PnL: {} {}",
                        instrument.ticker(),
                        quantity,
                        String.format("%.2f", averagePrice),
                        String.format("%.2f", currentPrice),
                        String.format("%.2f", pnl),
                        pos.getCurrentPrice().getCurrency());
            } else {
                logger.info("[{}] Позиций нет на счете {}", instrument.ticker(), accountId);
            }

        } catch (Exception e) {
            logger.error("[{}] Ошибка печати портфеля: {}", instrument.ticker(), e.getMessage());
        }
    }

    /**
     * Принудительная остановка бота с закрытием всех позиций по текущему FIGI.
     */
    public void stopAndClear() {
        logger.info("[{}] Плановая остановка бота. Закрытие всех позиций...", instrument.ticker());
        // Останавливаем логику в processCandle
        this.isLocked = false;
        this.isLongActive = false;
        this.isShortActive = false;

        // Закрываем позиции именно по этой бумаге на обоих счетах
        closeSpecificPosition(accountIdLong, instrument.figi());
        closeSpecificPosition(accountIdShort, instrument.figi());
    }

    private void closeSpecificPosition(String accountId, String figi) {
        var portfolio = api.getPortfolio(accountId);

        portfolio.getPositionsList().stream()
                .filter(p -> p.getFigi().equals(figi))
                .forEach(p -> {
                    // 1. Получаем количество в ШТУКАХ (акциях)
                    double totalShares = candleToDouble(p.getQuantity());

                    if (totalShares != 0) {
                        // 2. Конвертируем ШТУКИ в ЛОТЫ
                        long lotsToClose = (long) (Math.abs(totalShares) / instrument.lotSize());

                        if (lotsToClose == 0) {
                            logger.warn("[{}] Дробный остаток ({} шт) меньше 1 лота. Закрыть нельзя.",
                                    instrument.ticker(), totalShares);
                            return;
                        }

                        OrderDirection dir = totalShares > 0
                                ? OrderDirection.ORDER_DIRECTION_SELL
                                : OrderDirection.ORDER_DIRECTION_BUY;

                        logger.info("[{}] Плановая очистка: {} акций -> {} лотов на счете {}",
                                instrument.ticker(), totalShares, lotsToClose, accountId);

                        try {
                            logger.info("[{}] Лотов для закрытия: {}", instrument.ticker(), lotsToClose);
                            api.closePosition(accountId, figi, lotsToClose, dir);
                        } catch (Exception e) {
                            logger.error("[{}] Ошибка очистки: {}", instrument.ticker(), getStackTrace(e));
                        }
                    }
                });
    }

    /**
     * Выводит в лог историю операций по счету за указанный период.
     *
     */
    public void printOperations(String accountId, Instant from, Instant to) {
        try {
            // Вызываем через наш сервис
            var response = api.getOperations(accountId, instrument.figi(), from, to);

            if (response.getOperationsList().isEmpty()) {
                logger.info("[{}] Операций за период не найдено.", instrument.ticker());
            } else {
                DateTimeFormatter formatter = DateTimeFormatter
                        .ofPattern("yyyy-MM-dd HH:mm:ss")
                        .withZone(ZoneId.systemDefault());

                for (Operation op : response.getOperationsList()) {
                    String readableDate = formatter.format(Instant.ofEpochSecond(op.getDate().getSeconds()));
                    logger.info("[{}] {} | {} | {} {}",
                            instrument.ticker(),
                            readableDate,
                            op.getType(),
                            String.format("%.2f", moneyToDouble(op.getPayment())),
                            op.getPayment().getCurrency());
                }
            }
        } catch (Exception e) {
            logger.error("[{}] Ошибка при получении операций: {}", instrument.ticker(), e.getMessage());
        }
    }

    private String getProgressBar(double start, double end, double current, char symbol) {
        int size = 40;
        // Берем реальные границы, чтобы не было деления на ноль или отрицательных чисел
        double min = Math.min(start, end);
        double max = Math.max(start, end);

        if (max <= min) return "[Scale Error]";

        double position = (current - min) / (max - min);
        int index = (int) (position * size);

        // Ограничиваем индекс рамками шкалы
        index = Math.max(0, Math.min(size - 1, index));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (i == index) {
                sb.append(String.format(" %.2f ", current));
            } else {
                sb.append(symbol);
            }
        }
        return sb.toString();
    }

    protected void saveState() {
        BotState state = new BotState();
        state.supportLevel = this.supportLevel;
        state.resistanceLevel = this.resistanceLevel;
        state.totalProfit = this.totalProfit;
        state.longEntryPrice = this.longEntryPrice;
        state.shortEntryPrice = this.shortEntryPrice;
        state.lastClosedPrice = this.lastClosedPrice;
        state.isLocked = this.isLocked;
        state.isLongActive = this.isLongActive;
        state.isShortActive = this.isShortActive;
        state.lastAtr = this.lastAtr;
        state.trailingStopPrice = this.trailingStopPrice;
        state.status = status.name();

        repository.save(instrument.ticker(), state);
    }

    protected void loadState() {
        BotState state = repository.load(instrument.ticker());
        if (state != null) {
            this.supportLevel = state.supportLevel;
            this.resistanceLevel = state.resistanceLevel;
            this.totalProfit = state.totalProfit;
            this.longEntryPrice = state.longEntryPrice;
            this.shortEntryPrice = state.shortEntryPrice;
            this.lastClosedPrice = state.lastClosedPrice;
            this.isLocked = state.isLocked;
            this.isLongActive = state.isLongActive;
            this.isShortActive = state.isShortActive;
            this.lastAtr = state.lastAtr;
            this.trailingStopPrice = state.trailingStopPrice;
            this.status = BotStatus.valueOf(state.status);
        }
    }

    public void performFullReset(long initialCapital) {
        logger.info("[{}] Начинаю полный сброс бота с пересозданием счетов...", instrument.ticker());

        // 1. Пересоздаем счета через API и получаем новые ID
        String newLong = api.recreateSandboxAccount(this.accountIdLong, initialCapital);
        String newShort = api.recreateSandboxAccount(this.accountIdShort, initialCapital);

        if (newLong != null && newShort != null) {
            logger.info("[{}] Сброс завершен. Новые счета: L:{} S:{}", instrument.ticker(), newLong, newShort);
        } else {
            logger.error("[{}] Не удалось пересоздать счета!", instrument.ticker());
        }
    }

    private double safePrice(MoneyValue m, double referencePrice) {
        double apiPrice = moneyToDouble(m);

        // Если цена от API отличается от цены последней свечи более чем в 2 раза
        // или если она явно неадекватна (> 5000 для дешевых акций)
        if (referencePrice > 0 && (apiPrice > referencePrice * 2 || apiPrice < referencePrice / 2)) {
            logger.error("[{}] ГЛЮК API! Получено: {}, Свеча: {}. Использую цену свечи.",
                    instrument.ticker(), apiPrice, referencePrice);
            return referencePrice;
        }
        return apiPrice;
    }


    // Геттеры для проверки состояния в тестах
    public boolean isLocked() { return isLocked; }
    public boolean isLongActive() { return isLongActive; }
    public boolean isShortActive() { return isShortActive; }
    public double getTrailingStopPrice() { return trailingStopPrice; }
    public double getLastClosedPrice() { return lastClosedPrice; }
    public double getTotalProfit() { return totalProfit; }
    public double getLongEntryPrice() { return longEntryPrice; }
    public Instrument getInstrument() { return instrument; }
    public BotStatus getStatus() { return status; }
    public double getSupportLevel() { return supportLevel; }
    public double getResistanceLevel() { return resistanceLevel;}

    // Сеттеры для имитации условий (например, поставить цену входа перед пробоем)
    public void setLocked(boolean locked) { isLocked = locked; }
    public void setSupportLevel(double supportLevel) { this.supportLevel = supportLevel; }
    public void setResistanceLevel(double resistanceLevel) { this.resistanceLevel = resistanceLevel; }
    public void setLastAtr(double lastAtr) { this.lastAtr = lastAtr; }
    public void setLongEntryPrice(double price) { this.longEntryPrice = price; }
    public void setShortEntryPrice(double price) { this.shortEntryPrice = price; }
    public void setTotalProfit(double totalProfit) { this.totalProfit = totalProfit;}
    public void setStatus(BotStatus status) { this.status = status; }
    public void setLastLogTime(long lastLogTime) { this.lastLogTime = lastLogTime; }
}
