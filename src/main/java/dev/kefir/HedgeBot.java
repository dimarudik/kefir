package dev.kefir;

import com.google.protobuf.Timestamp;
import dev.kefir.model.BotState;
import dev.kefir.model.BotStatus;
import dev.kefir.model.Instrument;
import dev.kefir.repository.StateRepository;
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

public class HedgeBot {
    private static final Logger logger = LoggerFactory.getLogger(HedgeBot.class);

    private final TinkoffApiService api;
    private final StateRepository repository;
    private final MarketDataStreamServiceGrpc.MarketDataStreamServiceStub marketDataStreamStub;
    private StreamObserver<MarketDataRequest> currentRequestObserver;

    private int breakoutPushCount = 0;
    private double lastBreakoutPrice = 0;

    // Параметры инструмента
    private final dev.kefir.model.Instrument instrument;
    private final String accountIdLong;
    private final String accountIdShort;
    private final double atrMultiplier;
    private long currentQuantity;

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
                    String accountIdShort) {
        this.instrument = instrument;
        this.api = api;
        this.repository = repository;
        this.marketDataStreamStub = marketDataStreamStub;
        this.accountIdLong = accountIdLong;
        this.accountIdShort = accountIdShort;

        this.atrMultiplier = instrument.atrMultiplier();
        this.currentQuantity = instrument.quantity();
    }

    public String getAccountIdLong() {
        return accountIdLong;
    }

    public String getAccountIdShort() {
        return accountIdShort;
    }

    public void openHedge() {
        logger.info("[{}] Отправка одновременных заявок...", instrument.ticker());

        // Используем метод api.postOrder (или api.executeOrder),
        // который инкапсулирует в себе логику ретраев и выбора стаба
        CompletableFuture<PostOrderResponse> taskLong = CompletableFuture.supplyAsync(() ->
                api.postOrder(accountIdLong, instrument.figi(), currentQuantity, OrderDirection.ORDER_DIRECTION_BUY)
        );

        CompletableFuture<PostOrderResponse> taskShort = CompletableFuture.supplyAsync(() ->
                api.postOrder(accountIdShort, instrument.figi(), currentQuantity, OrderDirection.ORDER_DIRECTION_SELL)
        );

        try {
            // Ждем выполнения обеих задач
            CompletableFuture.allOf(taskLong, taskShort).join();

            this.longEntryPrice = moneyToDouble(taskLong.get().getExecutedOrderPrice());
            this.shortEntryPrice = moneyToDouble(taskShort.get().getExecutedOrderPrice());

            logger.info("[{}] Замок открыт! Вход LONG: {} | Вход SHORT: {}",
                    instrument.ticker(), longEntryPrice, shortEntryPrice);

            // Устанавливаем статус готовности
            this.isLocked = true;
            this.isLongActive = false;
            this.isShortActive = false;
            this.status = BotStatus.LOCKED;

        } catch (Exception e) {
            logger.error("[{}] Критическая ошибка при открытии замка: {}", instrument.ticker(), e.getMessage());
            this.status = BotStatus.PAUSE; // В случае провала уходим в паузу
        }
        saveState();
    }

    /**
     * Конвертирует MoneyValue в double для удобного вывода в лог.
     */
    private double moneyToDouble(MoneyValue m) {
        if (m == null) return 0.0;
        return m.getUnits() + m.getNano() / 1_000_000_000.0;
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
        if (status == BotStatus.PAUSE || status == BotStatus.INITIALIZING) return;

        double closePrice = candleToDouble(candle.getClose());
        logCurrentStatus(closePrice);

        // 1. Если мы в замке - проверяем только пробой
        if (status == BotStatus.LOCKED) {
            checkAndBreakHedge(closePrice);
            return; // ВСЕГДА выходим. Если пробой был - статус станет TRAILING,
            // и мы обработаем это уже на СЛЕДУЮЩЕЙ свече.
        }

        // 2. Если мы уже в трейлинге - ведем позицию
        if (status == BotStatus.TRAILING) {
            handleTrailingStop(closePrice);
        }
    }

    private void logCurrentStatus(double closePrice) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastLogTime < 10000) return;

        // 1. РЕЖИМ ЗАМКА (LOCKED)
        if (status == BotStatus.LOCKED) {
            String bar = getProgressBar(supportLevel, resistanceLevel, closePrice, '-');
            logger.info("[{} {} {} {}] {} % | Стоп: 0.00",
                    instrument.ticker(),
                    String.format("%.3f", supportLevel),
                    bar,
                    String.format("%.3f", resistanceLevel),
                    String.format("%.1f", (resistanceLevel / supportLevel - 1) * 100));

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
                logger.info("[{} {} {} {}] Вход: {} Выход: {}",
                        instrument.ticker(),
                        String.format("%.3f", trailingStopPrice),
                        bar,
                        String.format("%.3f", moveTrigger),
                        String.format("%.3f", longEntryPrice),
                        String.format("%.3f", lastClosedPrice));
            } else if (isShortActive) {
                moveTrigger = trailingStopPrice - offset;
                String bar = getProgressBar(moveTrigger, trailingStopPrice, closePrice, '<');
                logger.info("[{} {} {} {}] Вход: {} Выход: {}",
                        instrument.ticker(),
                        String.format("%.3f", moveTrigger),
                        bar,
                        String.format("%.3f", trailingStopPrice),
                        String.format("%.3f", shortEntryPrice),
                        String.format("%.3f", lastClosedPrice));
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
                logger.info("[{}] ↩ Откат вверх ({} < {}). Сброс счетчика к 1.",
                        instrument.ticker(), closePrice, lastBreakoutPrice);
            }

            lastBreakoutPrice = closePrice;
            logger.info("[{}] Цена: {} Пуш: {} ", instrument.ticker(), closePrice, breakoutPushCount);

            if (breakoutPushCount >= instrument.badPushThreshold()) {
                isLocked = false;
                isLongActive = true;
                logger.warn("[{}] >>> ПРОБОЙ ВВЕРХ ({} пушей)! Закрываем SHORT. Цена: {}",
                        instrument.ticker(), breakoutPushCount, closePrice);
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
                logger.info("[{}] ↩ Откат вниз ({} > {}). Сброс счетчика к 1.",
                        instrument.ticker(), closePrice, lastBreakoutPrice);
            }

            lastBreakoutPrice = closePrice;
            logger.info("[{}] Цена: {} Пуш: {} ", instrument.ticker(), closePrice, breakoutPushCount);

            if (breakoutPushCount >= instrument.badPushThreshold()) {
                isLocked = false;
                isShortActive = true;
                logger.warn("[{}] >>> ПРОБОЙ ВНИЗ ({} пушей)! Закрываем LONG. Цена: {}",
                        instrument.ticker(), breakoutPushCount, closePrice);
                executeHedgeBreak(accountIdLong, OrderDirection.ORDER_DIRECTION_SELL, closePrice, false);
                resetBreakoutCounter();
            }
            return;
        }

        // --- ЕСЛИ ЦЕНА ВЕРНУЛАСЬ В КАНАЛ ---
        if (breakoutPushCount > 0) {
            resetBreakoutCounter();
        }
    }

    private void resetBreakoutCounter() {
        breakoutPushCount = 0;
        lastBreakoutPrice = 0;
    }

    private void executeHedgeBreak(String accountId, OrderDirection direction, double closePrice, boolean isUp) {
        try {
            // 1. Узнаем реальный остаток в ШТУКАХ
            long realShares = api.getRealQuantity(accountId, instrument.figi());

            if (realShares <= 0) {
                logger.error("[{}] Ошибка вскрытия: Позиция не найдена!", instrument.ticker());
                return;
            }

            // 2. ВАЖНЫЙ МОМЕНТ: Конвертируем ШТУКИ в ЛОТЫ
            long lotsToClose = realShares / instrument.lotSize();

            if (lotsToClose == 0) {
                logger.warn("[{}] Недостаточно акций для закрытия даже 1 лота (всего {} шт)",
                        instrument.ticker(), realShares);
                return;
            }

            // 3. Передаем именно ЛОТЫ в метод closePosition
            var response = api.closePosition(accountId, instrument.figi(), lotsToClose, direction);
            this.lastClosedPrice = moneyToDouble(response.getExecutedOrderPrice());

            this.status = BotStatus.TRAILING;
            this.isLocked = false;

        } catch (Exception e) {
            logger.error("[{}] КРИТИЧЕСКАЯ ОШИБКА ВСКРЫТИЯ! {}", instrument.ticker(), e.getMessage());
            return;
        }

        // 2. Расчет начального стопа (выполнится только если замок реально вскрыт)
        double offset = lastAtr * atrMultiplier;
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

        logger.info("[{}] TRAILING активирован. Стоп: {} Выход: {}",
                instrument.ticker(), String.format("%.3f", trailingStopPrice), String.format("%.3f", lastClosedPrice));
        saveState();
    }

    private void handleTrailingStop(double closePrice) {
        double offset = lastAtr * instrument.atrMultiplier();
        double breakevenOffset = lastAtr * 0.2;

        if (isLongActive) {
            // 1. Базовый расчет по ATR
            double potentialStop = closePrice - offset;

            // 2. Мягкий безубыток (если тренд еще не начался, но цена в плюсе)
            double safeBreakeven = lastClosedPrice - breakevenOffset;
            if (potentialStop < safeBreakeven && closePrice > lastClosedPrice) {
                potentialStop = safeBreakeven;
            }

            // 3. Силовой перенос (защита от возврата в канал)
            if (closePrice > resistanceLevel && potentialStop < resistanceLevel) {
                potentialStop = resistanceLevel;
            }

            // 4. Жесткий безубыток (бетонируем точку входа, если цена выше неё)
            if (closePrice > lastClosedPrice && potentialStop < lastClosedPrice) {
                potentialStop = lastClosedPrice;
                if (potentialStop > trailingStopPrice) {
                    logger.info("[{}] 🛡 ЖЕСТКИЙ БЕЗУБЫТОК! Стоп прижат к входу: {}",
                            instrument.ticker(), String.format("%.3f", lastClosedPrice));
                }
            }

            // ПРИМЕНЕНИЕ ЛУЧШЕГО СТОПА
            if (potentialStop > trailingStopPrice) {
                trailingStopPrice = potentialStop;
                logger.info("[{}] ⬆ Подтягиваем стоп: {} (Цена: {})",
                        instrument.ticker(), String.format("%.3f", trailingStopPrice), String.format("%.3f", closePrice));
                saveState();
            }

            if (closePrice <= trailingStopPrice) {
                finalizeCycle(accountIdLong, OrderDirection.ORDER_DIRECTION_SELL, closePrice);
            }

        } else if (isShortActive) {
            // 1. Базовый расчет по ATR
            double potentialStop = closePrice + offset;

            // 2. Мягкий безубыток
            double safeBreakeven = lastClosedPrice + breakevenOffset;
            if (potentialStop > safeBreakeven && closePrice < lastClosedPrice) {
                potentialStop = safeBreakeven;
            }

            // 3. Силовой перенос
            if (closePrice < supportLevel && potentialStop > supportLevel) {
                potentialStop = supportLevel;
            }

            // 4. Жесткий безубыток
            if (closePrice < lastClosedPrice && potentialStop > lastClosedPrice) {
                potentialStop = lastClosedPrice;
                if (potentialStop < trailingStopPrice) {
                    logger.info("[{}] 🛡 ЖЕСТКИЙ БЕЗУБЫТОК! Стоп прижат к входу: {}",
                            instrument.ticker(), String.format("%.3f", lastClosedPrice));
                }
            }

            // ПРИМЕНЕНИЕ ЛУЧШЕГО СТОПА
            if (potentialStop < trailingStopPrice) {
                trailingStopPrice = potentialStop;
                logger.info("[{}] ⬇ Подтягиваем стоп: {} (Цена: {})",
                        instrument.ticker(), String.format("%.3f", trailingStopPrice), String.format("%.3f", closePrice));
                saveState();
            }

            if (closePrice >= trailingStopPrice) {
                finalizeCycle(accountIdShort, OrderDirection.ORDER_DIRECTION_BUY, closePrice);
            }
        }
    }

    private void finalizeCycle(String accountId, OrderDirection direction, double currentPrice) {
        double stopAtTrigger = this.trailingStopPrice;
        boolean wasLong = isLongActive;
        String mode = wasLong ? "LONG" : "SHORT";

        double exitPrice;
        long realShares; // Храним акции для профита
        long lotsToClose; // Храним лоты для API

        try {
            // 0. Уточняем реальный объем в ШТУКАХ
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

            // 2. ОТПРАВЛЯЕМ ЛОТЫ
            var response = api.closePosition(accountId, instrument.figi(), lotsToClose, direction);
            exitPrice = moneyToDouble(response.getExecutedOrderPrice());

            // 3. Смена статусов
            this.status = BotStatus.PAUSE;
            this.isLongActive = false;
            this.isShortActive = false;

            logger.warn("[{}] !!! ТРЕЙЛИНГ-СТОП ({}) СРАБОТАЛ !!!", instrument.ticker(), mode);
            logger.info("[{}] Закрыто лотов: {} ({} акций) | Выход: {}",
                    instrument.ticker(), lotsToClose, realShares, String.format("%.3f", exitPrice));

        } catch (Exception e) {
            logger.error("[{}] Ошибка финализации: {}. Ждем 5 секунд...", instrument.ticker(), e.getMessage());
            try { Thread.sleep(5000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            return;
        }

        // 4. Считаем профит по ШТУКАМ (realShares)
        double cycleProfit = (wasLong
                ? (exitPrice - longEntryPrice) + (shortEntryPrice - lastClosedPrice)
                : (shortEntryPrice - exitPrice) + (lastClosedPrice - longEntryPrice)) * realShares;

        totalProfit += cycleProfit;
        printCycleResults(cycleProfit);

        CompletableFuture.runAsync(this::resetCycle);
        saveState();
    }

    private void printCycleResults(double cycleProfit) {
        logger.info("---------------------------------------");
        logger.info("[{}] ЦИКЛ ЗАВЕРШЕН. Профит за круг: {} руб.", instrument.ticker(), String.format("%.3f", cycleProfit));
        logger.info("[{}] ОБЩИЙ ПРОФИТ: {} руб.", instrument.ticker(), String.format("%.3f", totalProfit));
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
            logger.info("[{}] Пауза перед новым циклом (60 сек)...", instrument.ticker());
            Thread.sleep(60000);

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
            logger.info("[{}] Используем уровни из файла: {} - {}",
                    instrument.ticker(), String.format("%.3f", supportLevel), String.format("%.3f", resistanceLevel));
            updateAtr(figi);
            return;
        }

        Instant now = Instant.now();
        // Увеличим период до 120 минут, чтобы канал был более обоснованным без ATR-добавки
        Instant from = now.minus(120, ChronoUnit.MINUTES);

        Timestamp toTs = Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build();
        Timestamp fromTs = Timestamp.newBuilder().setSeconds(from.getEpochSecond()).build();

        GetCandlesResponse response = api.getCandles(figi, fromTs, toTs);
        List<HistoricCandle> candles = response.getCandlesList();

        if (candles.isEmpty()) {
            logger.error("[{}] Не удалось получить свечи для расчета уровней!", instrument.ticker());
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

        // ATR обновляем ТОЛЬКО для ведения стоп-лосса
        updateAtr(figi);

        // УБИРАЕМ ATR ИЗ РАСЧЕТА УРОВНЕЙ
        // Теперь границы — это чистые High и Low периода
        this.resistanceLevel = max;
        this.supportLevel = min;

        logger.info("[{}] Уровни установлены строго по теням свечей: [ {} - {} ]",
                instrument.ticker(),
                String.format("%.3f", supportLevel),
                String.format("%.3f", resistanceLevel));
        saveState();
    }

    /**
     * Вычисляет среднюю волатильность (ATR) инструмента за последние 14 пятиминутных свечей.
     * Значение ATR используется для расчета дистанции динамического стоп-лосса (Trailing Stop).
     */
    private void updateAtr(String figi) {
        Instant now = Instant.now();
        Instant from = now.minus(2, ChronoUnit.HOURS);

        // Преобразуем Instant в формат gRPC Timestamp для сервиса
        Timestamp toTs = Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build();
        Timestamp fromTs = Timestamp.newBuilder().setSeconds(from.getEpochSecond()).build();

        try {
            // Вызов через декомпозированный сервис
            GetCandlesResponse response = api.getCandles(figi, fromTs, toTs);
            int candlesCount = response.getCandlesCount();

            // ПРОВЕРКА: Если свечей нет, выходим из метода, не меняя lastAtr
            if (candlesCount == 0) {
                logger.warn("[{}] Не удалось получить свечи для ATR. Используется старое значение: {}",
                        instrument.ticker(), String.format("%.3f", lastAtr));
                return;
            }

            double totalRange = 0;
            for (HistoricCandle candle : response.getCandlesList()) {
                totalRange += (candleToDouble(candle.getHigh()) - candleToDouble(candle.getLow()));
            }

            // Обновляем значение волатильности
            this.lastAtr = totalRange / candlesCount;
            logger.info("[{}] ATR обновлен: {} (на основе {} свечей)",
                    instrument.ticker(), String.format("%.3f", lastAtr), candlesCount);
            saveState();

        } catch (Exception e) {
            logger.error("[{}] Ошибка при обновлении ATR. Оставлено: {}",
                    instrument.ticker(), String.format("%.3f", lastAtr), e);
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
                this.currentQuantity = (long) Math.abs(candleToDouble(posLong.get().getQuantity()));
                this.longEntryPrice = moneyToDouble(posLong.get().getAveragePositionPrice());
                this.shortEntryPrice = moneyToDouble(posShort.get().getAveragePositionPrice());

                this.isLocked = true;
                this.isLongActive = false;
                this.isShortActive = false;
                this.status = BotStatus.LOCKED;

                logger.info("[{}] >>> ПОДХВАЧЕН ПОЛНЫЙ ЗАМОК: Объем {}, Вход L: {}, S: {}",
                        instrument.ticker(), currentQuantity, longEntryPrice, shortEntryPrice);
                saveState();
                return true;
            }

            // Сценарий 2: Активный Long
            else if (posLong.isPresent()) {
                this.currentQuantity = (long) Math.abs(candleToDouble(posLong.get().getQuantity()));
                this.longEntryPrice = moneyToDouble(posLong.get().getAveragePositionPrice());

                this.isLocked = false;
                this.isLongActive = true;
                this.isShortActive = false;
                this.status = BotStatus.TRAILING;

                double currentPrice = moneyToDouble(posLong.get().getCurrentPrice());
                this.trailingStopPrice = currentPrice - (lastAtr * atrMultiplier);

                logger.info("[{}] >>> ПОДХВАЧЕН АКТИВНЫЙ LONG: Объем {}, Вход: {}, Стоп: {}",
                        instrument.ticker(), currentQuantity, longEntryPrice, trailingStopPrice);
                saveState();
                return true;
            }

            // Сценарий 3: Активный Short
            else if (posShort.isPresent()) {
                this.currentQuantity = (long) Math.abs(candleToDouble(posShort.get().getQuantity()));
                this.shortEntryPrice = moneyToDouble(posShort.get().getAveragePositionPrice());

                this.isLocked = false;
                this.isLongActive = false;
                this.isShortActive = true;
                this.status = BotStatus.TRAILING;

                double currentPrice = moneyToDouble(posShort.get().getCurrentPrice());
                this.trailingStopPrice = currentPrice + (lastAtr * atrMultiplier);

                logger.info("[{}] >>> ПОДХВАЧЕН АКТИВНЫЙ SHORT: Объем {}, Вход: {}, Стоп: {}",
                        instrument.ticker(), currentQuantity, shortEntryPrice, trailingStopPrice);
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
                        String.format("%.3f", averagePrice),
                        String.format("%.3f", currentPrice),
                        String.format("%.3f", pnl),
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
                            api.closePosition(accountId, figi, lotsToClose, dir);
                        } catch (Exception e) {
                            logger.error("[{}] Ошибка очистки: {}", instrument.ticker(), e.getMessage());
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
                sb.append(String.format(" %.3f ", current));
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
