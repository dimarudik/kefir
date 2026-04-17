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

public class HedgeBot {
    private static final Logger logger = LoggerFactory.getLogger(HedgeBot.class);

    private final TinkoffApiService api;
    private final StateRepository repository;
    private final MarketDataStreamServiceGrpc.MarketDataStreamServiceStub marketDataStreamStub;

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

            logger.info("[{}] Замок открыт! L: {} | S: {}",
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
        StreamObserver<MarketDataResponse> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(MarketDataResponse response) {
                if (response.hasCandle()) {
                    processCandle(response.getCandle());
                }
            }

            @Override
            public void onError(Throwable t) {
                logger.error("[{}] Ошибка стрима: {}. Попытка переподключения через 5 секунд...", instrument.ticker(), t.getMessage());
                // Автоматический реконнект
                CompletableFuture.delayedExecutor(5, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
                    subscribeCandles();
                });
            }

            @Override
            public void onCompleted() {
                logger.warn("[{}] Стрим завершен сервером. Переподключаюсь...", instrument.ticker());
                subscribeCandles();
            }
        };

        // Открываем стрим заново
        StreamObserver<MarketDataRequest> requestObserver = marketDataStreamStub.marketDataStream(responseObserver);

        requestObserver.onNext(MarketDataRequest.newBuilder()
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
        if (status == BotStatus.PAUSE || status == BotStatus.INITIALIZING) {
            return;
        }

        double closePrice = candleToDouble(candle.getClose());

        logCurrentStatus(closePrice);

        if (isLocked) {
            checkAndBreakHedge(closePrice);
            // КРИТИЧНО: Если замок только что вскрылся, выходим из метода!
            // Не даем handleTrailingStop сработать на этой же свече.
            if (!isLocked) return;
        } else {
            handleTrailingStop(closePrice);
        }
    }

    private void logCurrentStatus(double closePrice) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastLogTime < 10000) return;

        boolean isTrailing = isLongActive || isShortActive;
        String bar = getProgressBar(supportLevel, resistanceLevel, closePrice, isTrailing);

        if (!isTrailing) {
            logger.info("[{} {} {} {}] | Стоп: 0.00",
                    instrument.ticker(), String.format("%.3f", supportLevel), bar, String.format("%.3f", resistanceLevel));
        } else {
            String mode = isLongActive ? "LONG" : "SHORT";
            logger.info("[{} {} {} {}] | Стоп: {} Цена: {} Вход: {} MODE: {}",
                    instrument.ticker(), String.format("%.3f", supportLevel), "---------------------",
                    String.format("%.3f", resistanceLevel), String.format("%.3f", trailingStopPrice),
                    String.format("%.3f", closePrice), String.format("%.3f", shortEntryPrice), mode);
        }
        lastLogTime = currentTime;
    }

    private void checkAndBreakHedge(double closePrice) {
        if (closePrice > resistanceLevel) {
            isLocked = false;
            isLongActive = true;
            logger.warn("[{}] >>> ПРОБОЙ ВВЕРХ! Закрываем SHORT", instrument.ticker());
            executeHedgeBreak(accountIdShort, OrderDirection.ORDER_DIRECTION_BUY, closePrice, true);
        } else if (closePrice < supportLevel) {
            isLocked = false;
            isShortActive = true;
            logger.warn("[{}] >>> ПРОБОЙ ВНИЗ! Закрываем LONG", instrument.ticker());
            executeHedgeBreak(accountIdLong, OrderDirection.ORDER_DIRECTION_SELL, closePrice, false);
        }
    }

    private void executeHedgeBreak(String accountId, OrderDirection direction, double closePrice, boolean isUp) {
        try {
            // Используем метод из TinkoffApiService
            var response = api.closePosition(accountId, instrument.figi(), currentQuantity, direction);
            this.lastClosedPrice = moneyToDouble(response.getExecutedOrderPrice());
        } catch (Exception e) {
            logger.error("[{}] Ошибка вскрытия: {}", instrument.ticker(), e.getMessage());
            this.lastClosedPrice = closePrice;
        }

        // 1. Устанавливаем статус TRAILING
        this.status = BotStatus.TRAILING;

        // 2. Расчет начального стопа (твой проверенный код с люфтом 20%)
        double offset = lastAtr * atrMultiplier;
        double breakevenOffset = lastAtr * 0.2;

        if (isUp) {
            double initialStop = closePrice - offset;
            double safeBreakeven = lastClosedPrice - breakevenOffset;
            this.trailingStopPrice = (initialStop < safeBreakeven && closePrice >= lastClosedPrice) ? safeBreakeven : initialStop;

            this.isLongActive = true;  // Подтверждаем флаги для логов
            this.isShortActive = false;
        } else {
            double initialStop = closePrice + offset;
            double safeBreakeven = lastClosedPrice + breakevenOffset;
            this.trailingStopPrice = (initialStop > safeBreakeven && closePrice <= lastClosedPrice) ? safeBreakeven : initialStop;

            this.isShortActive = true;
            this.isLongActive = false;
        }

        logger.info("[{}] Режим TRAILING активирован. Стоп: {}", instrument.ticker(), String.format("%.3f", trailingStopPrice));
        saveState();
    }

    private void handleTrailingStop(double closePrice) {
        double offset = lastAtr * instrument.atrMultiplier();
        // 20% от волатильности для "мягкого" безубытка
        double breakevenOffset = lastAtr * 0.2;

        if (isLongActive) {
            double potentialStop = closePrice - offset;
            double safeBreakeven = lastClosedPrice - breakevenOffset;

            // Вместо жесткого lastClosedPrice используем safeBreakeven
            if (potentialStop < safeBreakeven && closePrice > lastClosedPrice) {
                potentialStop = safeBreakeven;
            }

            if (potentialStop > trailingStopPrice) {
                trailingStopPrice = potentialStop;
                logger.info("[{}]: Подтягиваем стоп вверх: {} Цена: {} Цена входа: {}",
                        instrument.ticker(), String.format("%.3f", trailingStopPrice), String.format("%.3f", closePrice),
                        String.format("%.3f", longEntryPrice));
            }
            if (closePrice <= trailingStopPrice) {
                finalizeCycle(accountIdLong, OrderDirection.ORDER_DIRECTION_SELL, closePrice);
            }

        } else if (isShortActive) {
            double potentialStop = closePrice + offset;
            double safeBreakeven = lastClosedPrice + breakevenOffset;

            if (potentialStop > safeBreakeven && closePrice < lastClosedPrice) {
                potentialStop = safeBreakeven;
            }

            if (potentialStop < trailingStopPrice) {
                trailingStopPrice = potentialStop;
                logger.info("[{}] Подтягиваем стоп вниз: {} Цена: {} Цена входа: {}",
                        instrument.ticker(), String.format("%.3f", trailingStopPrice), String.format("%.3f", closePrice),
                        String.format("%.3f", longEntryPrice));
            }
            if (closePrice >= trailingStopPrice) {
                finalizeCycle(accountIdShort, OrderDirection.ORDER_DIRECTION_BUY, closePrice);
            }
        }
    }

    private void finalizeCycle(String accountId, OrderDirection direction, double currentPrice) {
        // 1. Блокируем обработку новых свечей статусом PAUSE
        this.status = BotStatus.PAUSE;

        boolean wasLong = isLongActive;
        String mode = wasLong ? "LONG" : "SHORT";

        // Запоминаем текущий стоп, пока он не обнулился
        double stopAtTrigger = this.trailingStopPrice;

        // 2. Сбрасываем флаги трейлинга
        isLongActive = false;
        isShortActive = false;

        // 3. Закрываем позицию через сервис
        double exitPrice;
        try {
            var response = api.closePosition(accountId, instrument.figi(), currentQuantity, direction);
            exitPrice = moneyToDouble(response.getExecutedOrderPrice());

            // Расширенный лог
            logger.warn("[{}] !!! ТРЕЙЛИНГ-СТОП ({}) СРАБОТАЛ !!!", instrument.ticker(), mode);
            logger.info("[{}] Детали: Стоп: {} | Свеча: {} | Исполнение: {} | Проскальзывание: {}",
                    instrument.ticker(),
                    String.format("%.3f", stopAtTrigger),
                    String.format("%.3f", currentPrice),
                    String.format("%.3f", exitPrice),
                    String.format("%.3f", Math.abs(exitPrice - stopAtTrigger)));

        } catch (Exception e) {
            logger.error("[{}] Ошибка при закрытии трейлинга: {}", instrument.ticker(), e.getMessage());
            exitPrice = stopAtTrigger;
        }

        // 4. Считаем профит
        double cycleProfit = wasLong
                ? (exitPrice - longEntryPrice) + (shortEntryPrice - lastClosedPrice)
                : (shortEntryPrice - exitPrice) + (lastClosedPrice - longEntryPrice);

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
        // Если уровни уже загружены из файла, не пересчитываем их
        if (this.supportLevel != 0 && this.resistanceLevel != 0) {
            logger.info("[{}] Используем уровни из файла: {} - {}",
                    instrument.ticker(), String.format("%.3f", supportLevel), String.format("%.3f", resistanceLevel));
            updateAtr(figi);
            return;
        }

        // Подготовка временных меток для запроса через сервис
        Instant now = Instant.now();
        Instant from = now.minus(90, ChronoUnit.MINUTES);

        // Преобразуем Instant в формат gRPC Timestamp для сервиса
        Timestamp toTs = Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build();
        Timestamp fromTs = Timestamp.newBuilder().setSeconds(from.getEpochSecond()).build();

        // Получаем свечи через наш новый API сервис
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

        updateAtr(figi);

        double multiplier = instrument.levelMultiplier();
        // Расчет уровней с использованием ATR и множителя
        this.resistanceLevel = max + (this.lastAtr * multiplier);
        this.supportLevel = min - (this.lastAtr * multiplier);

        logger.info("[{}] Уровни установлены заново: [ {} - {} ] с коэффициентом: {} ",
                instrument.ticker(),
                String.format("%.3f", supportLevel),
                String.format("%.3f", resistanceLevel),
                multiplier);
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
     * Если денег меньше целевой суммы, пополняет счет до 100 000 руб.
     */
    public void prepareSandboxAccounts() {
        // Пополняем баланс через сервис
        api.ensureSandboxBalance(accountIdLong, 40_000);
        api.ensureSandboxBalance(accountIdShort, 40_000);
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
        // Используем api.getPortfolio вместо прямого обращения к стабам
        var portfolio = api.getPortfolio(accountId);

        portfolio.getPositionsList().stream()
                .filter(p -> p.getFigi().equals(figi))
                .forEach(p -> {
                    double qtyInUnits = candleToDouble(p.getQuantity());
                    if (qtyInUnits != 0) {
                        // Используем текущий объем из настроек инструмента
                        long lotsToClose = currentQuantity;

                        OrderDirection dir = qtyInUnits > 0
                                ? OrderDirection.ORDER_DIRECTION_SELL
                                : OrderDirection.ORDER_DIRECTION_BUY;

                        logger.info("[{}] Закрытие остатка: {} лотов на счете {}",
                                instrument.ticker(), lotsToClose, accountId);

                        // Используем api.closePosition вместо closePositionWithResponse
                        api.closePosition(accountId, figi, lotsToClose, dir);
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

    private String getProgressBar(double min, double max, double closePrice, boolean isTailing) {
        int size = 40;
        StringBuilder sb = new StringBuilder();

        if (!isTailing) {
            // Режим ЗАМКА: рисуем положение между уровнями
            if (max <= min) return "Range Error";
            double position = (closePrice - min) / (max - min);
            int index = (int) (position * size);
            index = Math.max(0, Math.min(size - 1, index));

            for (int i = 0; i < size; i++) {
                if (i == index) {
                    sb.append(String.format(" %.2f ", closePrice));
                } else {
                    sb.append("-");
                }
            }
        } else {
            // Режим ТРЕЙЛИНГА: рисуем дистанцию до стопа
            // trailingStopPrice — это наш "старт", current — "финиш"
            double stop = this.trailingStopPrice;

            // Рисуем стрелочки в зависимости от направления
            if (isLongActive) {
                // LONG: Стоп <<<<<< Цена
                sb.append(String.format("%.2f [", stop));
                int dots = size - 5;
                for (int i = 0; i < dots; i++) sb.append("<");
                sb.append(String.format("] %.2f", closePrice));
            } else {
                // SHORT: Цена >>>>>> Стоп
                sb.append(String.format("%.2f [", closePrice));
                int dots = size - 5;
                for (int i = 0; i < dots; i++) sb.append(">");
                sb.append(String.format("] %.2f", stop));
            }
        }
        return sb.toString();
    }

    private void saveState() {
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

    public void loadState() {
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

    // Сеттеры для имитации условий (например, поставить цену входа перед пробоем)
    public void setLocked(boolean locked) { isLocked = locked; }
    public void setSupportLevel(double supportLevel) { this.supportLevel = supportLevel; }
    public void setResistanceLevel(double resistanceLevel) { this.resistanceLevel = resistanceLevel; }
    public void setLastAtr(double lastAtr) { this.lastAtr = lastAtr; }
    public void setLongEntryPrice(double price) { this.longEntryPrice = price; }
    public void setShortEntryPrice(double price) { this.shortEntryPrice = price; }
    public void setTotalProfit(double totalProfit) { this.totalProfit = totalProfit;}
    public void setStatus(BotStatus status) { this.status = status; }
}
