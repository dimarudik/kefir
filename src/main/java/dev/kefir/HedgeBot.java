package dev.kefir;

import com.google.protobuf.Timestamp;
import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tinkoff.piapi.contract.v1.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

public class HedgeBot {
    private static final Logger logger = LoggerFactory.getLogger(HedgeBot.class);

    private final Instrument instrument;

    private double totalProfit = 0.0; // Суммарная прибыль по всем циклам
    private double longEntryPrice = 0.0;
    private double shortEntryPrice = 0.0;
    private double lastClosedPrice = 0.0;

    private final boolean isSandbox;
    private long lastLogTime = 0;
    private final UsersServiceGrpc.UsersServiceBlockingStub userStub;
    private final OrdersServiceGrpc.OrdersServiceBlockingStub ordersStub;
    private final SandboxServiceGrpc.SandboxServiceBlockingStub sandboxStub;
    private final OperationsServiceGrpc.OperationsServiceBlockingStub operationsStub;
    private volatile boolean isLocked = true;
    private double resistanceLevel = 0;
    private double supportLevel = 0;
    private String accountIdLong;
    private String accountIdShort;
    private long currentQuantity;
    private volatile double trailingStopPrice = 0;
    private double lastAtr = 0;
    private boolean isLongActive = false;
    private boolean isShortActive = false;
    private final MarketDataServiceGrpc.MarketDataServiceBlockingStub marketDataBlockingStub;
    private final MarketDataStreamServiceGrpc.MarketDataStreamServiceStub marketDataAsyncStub;


    /**
     * Инициализирует gRPC-клиент для работы с Tinkoff Invest API.
     * Создает каналы связи и Stub-сервисы с поддержкой авторизации через CallCredentials.
     *
     * @param token Токен доступа Tinkoff Invest (Full Access)
     * @param accountIdLong ID брокерского счета для совершения Long-сделок
     * @param accountIdShort ID брокерского счета для совершения Short-сделок
     */
    public HedgeBot(Instrument instrument, String token, String accountIdLong, String accountIdShort, boolean isSandbox) {
        this.isSandbox = isSandbox;
        this.accountIdLong = accountIdLong;
        this.accountIdShort = accountIdShort;
        this.instrument = instrument;
        this.currentQuantity = instrument.quantity();
        String host = isSandbox ? "sandbox-invest-public-api.tinkoff.ru" : "invest-public-api.tinkoff.ru";

        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, 443)
                .useTransportSecurity()
                .keepAliveTime(30, TimeUnit.SECONDS) // Поддерживать соединение "живым"
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .build();

        // Создаем Credentials для автоматической авторизации
        CallCredentials credentials = new CallCredentials() {
            @Override
            public void applyRequestMetadata(RequestInfo requestInfo, Executor executor, MetadataApplier metadataApplier) {
                executor.execute(() -> {
                    try {
                        Metadata headers = new Metadata();
                        Metadata.Key<String> authKey = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
                        headers.put(authKey, "Bearer " + token);
                        metadataApplier.apply(headers);
                    } catch (Throwable e) {
                        metadataApplier.fail(Status.UNAUTHENTICATED.withCause(e));
                    }
                });
            }
        };

        // Инициализируем Stub с использованием credentials
        this.ordersStub = OrdersServiceGrpc.newBlockingStub(channel).withCallCredentials(credentials);
        this.userStub = UsersServiceGrpc.newBlockingStub(channel).withCallCredentials(credentials);
        this.sandboxStub = SandboxServiceGrpc.newBlockingStub(channel).withCallCredentials(credentials);
        this.marketDataAsyncStub = MarketDataStreamServiceGrpc.newStub(channel).withCallCredentials(credentials);
        this.marketDataBlockingStub = MarketDataServiceGrpc.newBlockingStub(channel).withCallCredentials(credentials);
        this.operationsStub = OperationsServiceGrpc.newBlockingStub(channel).withCallCredentials(credentials);
    }

    public String getAccountIdLong() {
        return accountIdLong;
    }

    public String getAccountIdShort() {
        return accountIdShort;
    }

    /**
     * Одновременно открывает две противоположные позиции (Long и Short) по рыночной цене.
     * Использует CompletableFuture для минимизации временного лага между сделками.
     * Переводит робота в состояние "isLocked" (замок).
     *
     * @param accLong ID счета для покупки
     * @param accShort ID счета для продажи
     */
    public void openHedge(String accLong, String accShort) {
//        this.currentFigi = figi;
//        this.currentQuantity = quantity;

        PostOrderRequest longRequest = createOrderRequest(instrument.figi(), currentQuantity,
                OrderDirection.ORDER_DIRECTION_BUY, accLong, OrderType.ORDER_TYPE_MARKET);
        PostOrderRequest shortRequest = createOrderRequest(instrument.figi(), currentQuantity,
                OrderDirection.ORDER_DIRECTION_SELL, accShort, OrderType.ORDER_TYPE_MARKET);

        logger.info("Отправка одновременных заявок...");

        // Асинхронный запуск в два потока
        var task1 = CompletableFuture.supplyAsync(() -> closePositionWithResponse(
                accLong, instrument.figi(), currentQuantity, OrderDirection.ORDER_DIRECTION_BUY));
        var task2 = CompletableFuture.supplyAsync(() -> closePositionWithResponse(
                accShort, instrument.figi(), currentQuantity, OrderDirection.ORDER_DIRECTION_SELL));

        CompletableFuture.allOf(task1, task2).join();

        try {
            PostOrderResponse longRes = task1.get();
            PostOrderResponse shortRes = task2.get();

            this.longEntryPrice = moneyToDouble(longRes.getExecutedOrderPrice());
            this.shortEntryPrice = moneyToDouble(shortRes.getExecutedOrderPrice());

            logger.info("[{}]: Замок открыт! LONG цена: {} | SHORT цена: {}", instrument.ticker(),
                    moneyToDouble(longRes.getExecutedOrderPrice()),
                    moneyToDouble(shortRes.getExecutedOrderPrice()));
        } catch (Exception e) {
            logger.error("[{}]: Ошибка при получении цен исполнения", instrument.ticker(), e);
        }

        logger.info("[{}]: Замок успешно открыт.", instrument.ticker());
    }

/*
    private PostOrderResponse executeOrderWithResponse(PostOrderRequest request) {
        if (isSandbox) {
            return sandboxStub.postSandboxOrder(request);
        } else {
            return ordersStub.postOrder(request);
        }
    }
*/

    private PostOrderRequest createOrderRequest(String figi, long quantity, OrderDirection direction, String accountId, OrderType orderType) {
        return PostOrderRequest.newBuilder()
                .setFigi(figi)
                .setQuantity(quantity)
                .setDirection(direction)
                .setAccountId(accountId)
                .setOrderType(orderType)
                .setOrderId(java.util.UUID.randomUUID().toString())
                .build();
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
     * @param figi Идентификатор финансового инструмента
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
        StreamObserver<MarketDataRequest> requestObserver = marketDataAsyncStub.marketDataStream(responseObserver);

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
    private synchronized void processCandle(Candle candle) { // Добавлен synchronized
        double closePrice = candleToDouble(candle.getClose());
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastLogTime >= 10000) {
            logger.info("[{} {} {}] Анализ свечи. Close: {} | Текущий стоп: {}",
                    instrument.figi(), supportLevel, resistanceLevel, closePrice, trailingStopPrice);
            lastLogTime = currentTime;
        }

        if (isLocked) {
            if (closePrice > resistanceLevel) {
                isLocked = false; // Сбрасываем флаг сразу
                logger.warn("[{}] >>> ПРОБОЙ ВВЕРХ! Закрываем убыточный SHORT", instrument.figi());
                var response = closePositionWithResponse(accountIdShort, instrument.figi(), currentQuantity, OrderDirection.ORDER_DIRECTION_BUY);

                isLongActive = true;
                trailingStopPrice = closePrice - (lastAtr * 2);
                logger.info("[{}] Активирован режим LONG. Начальный стоп: {}", instrument.figi(), trailingStopPrice);

            } else if (closePrice < supportLevel) {
                isLocked = false; // Сбрасываем флаг сразу
                logger.warn("[{}] >>> ПРОБОЙ ВНИЗ! Закрываем убыточный LONG", instrument.figi());
                var response = closePositionWithResponse(accountIdLong, instrument.figi(), currentQuantity, OrderDirection.ORDER_DIRECTION_SELL);

                isShortActive = true;
                trailingStopPrice = closePrice + (lastAtr * 2);
                logger.info("[{}] Активирован режим SHORT. Начальный стоп: {}", instrument.figi(), trailingStopPrice);
            }

        } else {
            if (isLongActive) {
                double potentialStop = closePrice - (lastAtr * 1.5);
                if (potentialStop > trailingStopPrice) {
                    trailingStopPrice = potentialStop;
                    logger.info("Подтягиваем стоп вверх: {} {}", trailingStopPrice, instrument.figi());
                }

                if (closePrice <= trailingStopPrice) {
                    isLongActive = false; // ПРЕДОХРАНИТЕЛЬ: выключаем режим ДО сетевого вызова
                    logger.warn("[{}] !!! ТРЕЙЛИНГ-СТОП (LONG) СРАБОТАЛ !!!", instrument.figi());

                    // ИСПРАВЛЕНО: Закрываем LONG счет (ПРОДАЖЕЙ)
                    var response = closePositionWithResponse(accountIdLong, instrument.figi(), currentQuantity, OrderDirection.ORDER_DIRECTION_SELL);
                    double longExitPrice = moneyToDouble(response.getExecutedOrderPrice());

                    // ФОРМУЛА LONG: (Цена выхода Long - Цена входа Long) + (Цена выхода Short[lastClosedPrice] - Цена входа Short)
                    double cycleProfit = (longExitPrice - longEntryPrice) + (lastClosedPrice - shortEntryPrice);
                    totalProfit += cycleProfit;

                    logger.info("DEBUG PROFIT [{}]: shortEntry={}, shortExit={}, lastClosed={}, longEntry={}",
                            instrument.figi(), shortEntryPrice, longExitPrice, lastClosedPrice, longEntryPrice);

                    printCycleResults(cycleProfit);
                    CompletableFuture.runAsync(this::resetCycle);
                }

            } else if (isShortActive) {
                double potentialStop = closePrice + (lastAtr * 1.5);
                if (potentialStop < trailingStopPrice) {
                    trailingStopPrice = potentialStop;
                    logger.info("Подтягиваем стоп вниз: {} {}", trailingStopPrice, instrument.figi());
                }

                if (closePrice >= trailingStopPrice) {
                    isShortActive = false; // ПРЕДОХРАНИТЕЛЬ
                    logger.warn("[{}] !!! ТРЕЙЛИНГ-СТОП (SHORT) СРАБОТАЛ !!!", instrument.figi());

                    // ИСПРАВЛЕНО: Закрываем SHORT счет (ПОКУПКОЙ)
                    var response = closePositionWithResponse(accountIdShort, instrument.figi(), currentQuantity, OrderDirection.ORDER_DIRECTION_BUY);
                    double shortExitPrice = moneyToDouble(response.getExecutedOrderPrice());

                    // ФОРМУЛА SHORT: (Цена входа Short - Цена выхода Short) + (Цена выхода Long[lastClosedPrice] - Цена входа Long)
                    double cycleProfit = (shortEntryPrice - shortExitPrice) + (lastClosedPrice - longEntryPrice);
                    totalProfit += cycleProfit;

                    logger.info("DEBUG PROFIT [{}]: shortEntry={}, shortExit={}, lastClosed={}, longEntry={}",
                            instrument.figi(), shortEntryPrice, shortExitPrice, lastClosedPrice, longEntryPrice);

                    printCycleResults(cycleProfit);
                    CompletableFuture.runAsync(this::resetCycle);
                }
            }
        }
    }

    private void printCycleResults(double cycleProfit) {
        logger.info("---------------------------------------");
        logger.info("[{}] ЦИКЛ ЗАВЕРШЕН. Профит за круг: {} руб.", instrument.figi(), String.format("%.2f", cycleProfit));
        logger.info("[{}] ОБЩИЙ ПРОФИТ БОТА: {} руб.", instrument.figi(), String.format("%.2f", totalProfit));
        logger.info("---------------------------------------");
    }

    /**
     * Выполняет полный сброс состояния робота и подготовку к новому циклу.
     * Включает минутную паузу, перерасчет уровней и открытие нового "замка".
     * Запускается асинхронно, чтобы не блокировать поток рыночных данных.
     */
    private void resetCycle() {
        logger.info("--- [{}] ЗАВЕРШЕНИЕ СДЕЛКИ: СБРОС СОСТОЯНИЯ ---", instrument.figi());
        this.isLongActive = false;
        this.isShortActive = false;
        this.trailingStopPrice = 0;

        this.longEntryPrice = 0;
        this.shortEntryPrice = 0;
        this.lastClosedPrice = 0;

        try {
            // Пауза 1 минута, чтобы не войти на той же свече
            logger.info("[{}] Пауза перед новым циклом (60 сек)...", instrument.figi());
            Thread.sleep(60000);

            // Обновляем волатильность и уровни перед новым входом
            initLevels(instrument.figi());

            // Входим в новый замок
            openHedge(accountIdLong, accountIdShort);

            // Возвращаем флаг готовности к анализу
            this.isLocked = true;
            logger.info("--- [{}] НОВЫЙ ЦИКЛ ЗАПУЩЕН ---", instrument.figi());
        } catch (InterruptedException e) {
            logger.error("[{}] Критическая ошибка при паузе цикла", instrument.figi(), e);
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
        // Берем данные за последние несколько часов
        Instant now = java.time.Instant.now();
        Instant from = now.minus(4, java.time.temporal.ChronoUnit.HOURS);

        GetCandlesResponse response = marketDataBlockingStub.getCandles(GetCandlesRequest.newBuilder()
                .setFigi(figi)
                .setFrom(Timestamp.newBuilder().setSeconds(from.getEpochSecond()).build())
                .setTo(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build())
                .setInterval(CandleInterval.CANDLE_INTERVAL_HOUR)
                .build());

//        logger.info("Свечи за 4 часа: {}", response.getCandlesList());

        double max = 0;
        double min = Double.MAX_VALUE;

        for (HistoricCandle candle : response.getCandlesList()) {
            double high = candleToDouble(candle.getHigh());
            double low = candleToDouble(candle.getLow());
            if (high > max) max = high;
            if (low < min) min = low;
        }

        this.resistanceLevel = max;
        this.supportLevel = min;
        updateAtr(figi);
        logger.info("Уровни установлены:  Поддержка = {} , Сопротивление = {}", min, max);
    }

    /**
     * Закрывает позицию и возвращает ответ сервера с деталями исполнения.
     */
    private PostOrderResponse closePositionWithResponse(String accountId, String figi, long quantity, OrderDirection direction) {
        var request = PostOrderRequest.newBuilder()
                .setFigi(figi)
                .setQuantity(quantity)
                .setDirection(direction)
                .setAccountId(accountId)
                .setOrderType(OrderType.ORDER_TYPE_MARKET)
                .setOrderId(UUID.randomUUID().toString())
                .build();

        PostOrderResponse response;
        if (isSandbox) {
            response = sandboxStub.postSandboxOrder(request);
        } else {
            response = ordersStub.postOrder(request);
        }

        double executedPrice = moneyToDouble(response.getExecutedOrderPrice());

        // Сохраняем цену исполнения.
        // Если это закрытие первой (убыточной) ноги, это значение будет использовано для расчета прибыли в конце цикла.
        this.lastClosedPrice = executedPrice;

        logger.info("[{}] >>> ПОЗИЦИЯ ИСПОЛНЕНА на счете: {}. Цена: {} {}",
                instrument.ticker(), accountId, executedPrice, response.getExecutedOrderPrice().getCurrency());

        return response;
    }

    /**
     * Закрывает позицию по рыночной цене и выводит цену исполнения в лог.
     */
    private void closePosition(String accountId, String figi, long quantity, OrderDirection direction) {
        var request = PostOrderRequest.newBuilder()
                .setFigi(figi)
                .setQuantity(quantity)
                .setDirection(direction)
                .setAccountId(accountId)
                .setOrderType(OrderType.ORDER_TYPE_MARKET)
                .setOrderId(UUID.randomUUID().toString())
                .build();

        try {
            // Выполняем ордер и получаем ответ
            PostOrderResponse response;
            if (isSandbox) {
                response = sandboxStub.postSandboxOrder(request);
            } else {
                response = ordersStub.postOrder(request);
            }

            // Вытаскиваем цену из ответа сервера
            double executedPrice = moneyToDouble(response.getExecutedOrderPrice());

            logger.info(">>> ПОЗИЦИЯ ЗАКРЫТА на счете: {}. Цена исполнения: {} {}",
                    accountId, executedPrice, response.getExecutedOrderPrice().getCurrency());

        } catch (Exception e) {
            logger.error("Ошибка при закрытии позиции на счете {}", accountId, e);
        }
    }

    /**
     * Вычисляет среднюю волатильность (ATR) инструмента за последние 14 пятиминутных свечей.
     * Значение ATR используется для расчета дистанции динамического стоп-лосса (Trailing Stop).
     */
    private void updateAtr(String figi) {
        Instant now = java.time.Instant.now();
        Instant from = now.minus(2, java.time.temporal.ChronoUnit.HOURS);

        try {
            GetCandlesResponse response = marketDataBlockingStub.getCandles(GetCandlesRequest.newBuilder()
                    .setFigi(figi)
                    .setFrom(com.google.protobuf.Timestamp.newBuilder().setSeconds(from.getEpochSecond()).build())
                    .setTo(com.google.protobuf.Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build())
                    .setInterval(CandleInterval.CANDLE_INTERVAL_5_MIN)
                    .build());

            int candlesCount = response.getCandlesCount();

            // ПРОВЕРКА: Если свечей нет, выходим из метода, не меняя lastAtr
            if (candlesCount == 0) {
                logger.warn("Не удалось получить свечи для расчета ATR для FIGI: {}. Используется старое значение: {}", figi, lastAtr);
                return;
            }

            double totalRange = 0;
            for (HistoricCandle candle : response.getCandlesList()) {
                totalRange += (candleToDouble(candle.getHigh()) - candleToDouble(candle.getLow()));
            }

            // Обновляем значение волатильности
            this.lastAtr = totalRange / candlesCount;
            logger.info("Волатильность (ATR) обновлена: {} (на основе {} свечей)", lastAtr, candlesCount);

        } catch (Exception e) {
            logger.error("Ошибка при обновлении ATR для FIGI: {}. Оставлено старое значение: {}", figi, lastAtr, e);
        }
    }

    private void executeOrder(PostOrderRequest request) {
        if (isSandbox) {
            sandboxStub.postSandboxOrder(request);
            logger.info("[SANDBOX] Ордер отправлен: {}", request.getOrderId());
        } else {
            ordersStub.postOrder(request);
            logger.info("[REAL] Ордер отправлен: {}", request.getOrderId());
        }
    }

    /**
     * Выводит в консоль список всех доступных реальных счетов.
     * Используйте этот метод один раз, чтобы узнать ID для вставки в main.
     */
    public void printRealAccounts() {
        try {
            var response = userStub.getAccounts(GetAccountsRequest.newBuilder().build());
            for (Account acc : response.getAccountsList()) {
                logger.info("Счет: {} | ID: {} | Статус: {}", acc.getName(), acc.getId(), acc.getStatus());
            }
        } catch (Exception e) {
            logger.error("Ошибка при получении списка реальных счетов", e);
        }
    }

    /**
     * Подготавливает счета для работы в песочнице.
     * Проверяет наличие счетов, а также баланс на них.
     * Если денег меньше целевой суммы, пополняет счет до 100 000 руб.
     */
    public void prepareSandboxAccounts() {
        if (!isSandbox) {
            logger.error("Метод предназначен только для режима Sandbox!");
            return;
        }

        try {
            // 1. Получаем счета
            var accountsResponse = sandboxStub.getSandboxAccounts(GetAccountsRequest.newBuilder().build());
            List<Account> accounts = accountsResponse.getAccountsList();

            if (accounts.size() >= 2) {
                this.accountIdLong = accounts.get(0).getId();
                this.accountIdShort = accounts.get(1).getId();
                logger.info("Используем существующие счета Sandbox: Long={}, Short={}", accountIdLong, accountIdShort);
            } else {
                logger.info("Создаем новые счета в Sandbox...");
                this.accountIdLong = sandboxStub.openSandboxAccount(ru.tinkoff.piapi.contract.v1.OpenSandboxAccountRequest.newBuilder().build()).getAccountId();
                this.accountIdShort = sandboxStub.openSandboxAccount(ru.tinkoff.piapi.contract.v1.OpenSandboxAccountRequest.newBuilder().build()).getAccountId();
            }

            // 2. Проверяем и пополняем баланс для каждого счета
            ensureBalance(accountIdLong, 20_000);
            ensureBalance(accountIdShort, 20_000);

        } catch (Exception e) {
            logger.error("Критическая ошибка при подготовке Sandbox счетов", e);
        }
    }

    /**
     * Вспомогательный метод для проверки и пополнения баланса до целевой суммы.
     */
    private void ensureBalance(String accountId, long targetUnits) {
        try {
            var portfolio = sandboxStub.getSandboxPortfolio(PortfolioRequest.newBuilder()
                    .setAccountId(accountId)
                    .build());

            // Находим рублевый остаток в портфеле
            long currentUnits = portfolio.getTotalAmountCurrencies().getUnits();

            if (currentUnits < targetUnits) {
                long diff = targetUnits - currentUnits;
                var deposit = MoneyValue.newBuilder()
                        .setCurrency("rub")
                        .setUnits(diff)
                        .build();

                sandboxStub.sandboxPayIn(SandboxPayInRequest.newBuilder()
                        .setAccountId(accountId)
                        .setAmount(deposit)
                        .build());

                logger.info("Баланс счета {} пополнен на {} RUB. Текущий баланс: {} RUB", accountId, diff, targetUnits);
            } else {
                logger.info("Баланс счета {} достаточен: {} RUB", accountId, currentUnits);
            }
        } catch (Exception e) {
            logger.error("Ошибка при проверке баланса счета {}", accountId, e);
        }
    }

    /**
     * Списывает все свободные денежные средства со счета в песочнице,
     * приводя рублевый баланс к нулю.
     *
     * @param accountId ID счета для обнуления
     */
    public void resetBalanceToZero(String accountId) {
        if (!isSandbox) {
            logger.error("Обнуление баланса доступно только в режиме Sandbox!");
            return;
        }

        try {
            // 1. Получаем текущий портфель, чтобы узнать остаток кеша
            var portfolio = sandboxStub.getSandboxPortfolio(ru.tinkoff.piapi.contract.v1.PortfolioRequest.newBuilder()
                    .setAccountId(accountId)
                    .build());

            // Находим рублевый остаток
            var cash = portfolio.getTotalAmountCurrencies();
            long units = cash.getUnits();
            int nanos = cash.getNano();

            if (units > 0 || nanos > 0) {
                // 2. Списываем сумму (передаем отрицательное значение)
                var withdraw = ru.tinkoff.piapi.contract.v1.MoneyValue.newBuilder()
                        .setCurrency("rub")
                        .setUnits(-units)
                        .setNano(-nanos)
                        .build();

                sandboxStub.sandboxPayIn(ru.tinkoff.piapi.contract.v1.SandboxPayInRequest.newBuilder()
                        .setAccountId(accountId)
                        .setAmount(withdraw)
                        .build());

                logger.info("Баланс счета {} обнулен. Списано: {} RUB", accountId, (units + nanos / 1_000_000_000.0));
            } else {
                logger.info("Баланс счета {} уже равен нулю или отрицательный.", accountId);
            }
        } catch (Exception e) {
            logger.error("Ошибка при обнулении баланса счета {}", accountId, e);
        }
    }

    /**
     * Проверяет наличие открытого замка на счетах.
     * Если замок найден, восстанавливает состояние бота без открытия новых сделок.
     * @return true если замок найден и подхвачен, false если счета пустые
     */
    public boolean tryAttachToExistingHedge() {
        try {
            var portfolioLong = getPortfolio(accountIdLong);
            var portfolioShort = getPortfolio(accountIdShort);

            // Ищем позиции по FIGI
            var posLong = portfolioLong.getPositionsList().stream()
                    .filter(p -> p.getFigi().equals(instrument.figi()) && candleToDouble(p.getQuantity()) > 0)
                    .findFirst();

            var posShort = portfolioShort.getPositionsList().stream()
                    .filter(p -> p.getFigi().equals(instrument.figi()) && candleToDouble(p.getQuantity()) < 0)
                    .findFirst();

//            this.currentFigi = figi;

            // Сценарий 1: Полный замок
            if (posLong.isPresent() && posShort.isPresent()) {
                this.currentQuantity = (long) Math.abs(candleToDouble(posLong.get().getQuantity()));
                this.isLocked = true;
                this.isLongActive = false;
                this.isShortActive = false;
                logger.info(">>> ПОДХВАЧЕН ПОЛНЫЙ ЗАМОК: FIGI {}, Объем {}", instrument.figi(), currentQuantity);
                return true;
            }

            // Сценарий 2: Активный Long (уже был пробой вверх, шорт закрыт)
            else if (posLong.isPresent()) {
                this.currentQuantity = (long) Math.abs(candleToDouble(posLong.get().getQuantity()));
                this.isLocked = false;
                this.isLongActive = true;
                this.isShortActive = false;
                // Устанавливаем стоп на основе текущей цены, так как старый стоп потерян
                double currentPrice = moneyToDouble(posLong.get().getCurrentPrice());
                this.trailingStopPrice = currentPrice - (lastAtr * 2);
                logger.info(">>> ПОДХВАЧЕН АКТИВНЫЙ LONG: Объем {}, Стоп установлен на {}", currentQuantity, trailingStopPrice);
                return true;
            }

            // Сценарий 3: Активный Short (уже был пробой вниз, лонг закрыт)
            else if (posShort.isPresent()) {
                this.currentQuantity = (long) Math.abs(candleToDouble(posShort.get().getQuantity()));
                this.isLocked = false;
                this.isLongActive = false;
                this.isShortActive = true;
                // Устанавливаем стоп на основе текущей цены
                double currentPrice = moneyToDouble(posShort.get().getCurrentPrice());
                this.trailingStopPrice = currentPrice + (lastAtr * 2);
                logger.info(">>> ПОДХВАЧЕН АКТИВНЫЙ SHORT: Объем {}, Стоп установлен на {}", currentQuantity, trailingStopPrice);
                return true;
            }

        } catch (Exception e) {
            logger.error("Ошибка при восстановлении состояния позиций", e);
        }
        return false;
    }

    private ru.tinkoff.piapi.contract.v1.PortfolioResponse getPortfolio(String accountId) {
        var request = ru.tinkoff.piapi.contract.v1.PortfolioRequest.newBuilder()
                .setAccountId(accountId)
                .build();

        if (isSandbox) {
            // В песочнице портфель берется из SandboxService
            return sandboxStub.getSandboxPortfolio(request);
        } else {
            // На реале портфель берется из OperationsService
            return operationsStub.getPortfolio(request);
        }
    }

    /**
     * Полностью закрывает все открытые позиции на указанном счете.
     * Полезно для очистки счетов в песочнице перед новым тестом.
     *
     * @param accountId ID счета для очистки
     */
    public void closeAllPositions(String accountId) {
        logger.info("Запуск полной очистки позиций на счете: {}", accountId);
        try {
            // 1. Получаем текущий портфель
            var portfolio = getPortfolio(accountId);

            for (var position : portfolio.getPositionsList()) {
                String figi = position.getFigi();

                // Игнорируем рубли (инструмент RUB000UTSTOM)
                if (figi.equalsIgnoreCase("RUB000UTSTOM")) {
                    continue;
                }

                double quantity = candleToDouble(position.getQuantity());

                // Если количество положительное — мы в Long (нужно продать)
                // Если отрицательное — мы в Short (нужно купить)
                if (quantity != 0) {
                    OrderDirection direction = quantity > 0
                            ? OrderDirection.ORDER_DIRECTION_SELL
                            : OrderDirection.ORDER_DIRECTION_BUY;

                    long absQuantity = (long) Math.abs(quantity);

                    logger.info("Закрытие позиции: FIGI {}, Объем {}, Направление {}", figi, absQuantity, direction);

                    // Используем уже существующий у нас метод закрытия
                    closePosition(accountId, figi, absQuantity, direction);
                }
            }
            logger.info("Очистка счета {} завершена.", accountId);
        } catch (Exception e) {
            logger.error("Ошибка при очистке позиций на счете {}", accountId, e);
        }
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
            var portfolio = getPortfolio(accountId);

            // 1. Выводим позиции по ценным бумагам
            if (portfolio.getPositionsList().isEmpty()) {
                logger.info("Бумаги в портфеле отсутствуют.");
            } else {
                for (var position : portfolio.getPositionsList()) {
                    double quantity = candleToDouble(position.getQuantity());
                    logger.info("Инструмент (FIGI): {} | Количество: {} | Текущая цена: {} {}",
                            position.getFigi(),
                            quantity,
                            moneyToDouble(position.getCurrentPrice()),
                            position.getCurrentPrice().getCurrency());
                }
            }

            // 2. Выводим баланс валюты (наличные)
            var cash = portfolio.getTotalAmountCurrencies();
            logger.info("Свободные средства: {} {}",
                    moneyToDouble(cash),
                    cash.getCurrency());

            logger.info("============================================================================================");
        } catch (Exception e) {
            logger.error("Ошибка при получении портфеля счета {}", accountId, e);
        }
    }

    /**
     * Принудительная остановка бота с закрытием всех позиций по текущему FIGI.
     */
    public void stopAndClear() {
        logger.info("[{}] Плановая остановка бота. Закрытие всех позиций...", instrument.figi());
        // Останавливаем логику в processCandle
        this.isLocked = false;
        this.isLongActive = false;
        this.isShortActive = false;

        // Закрываем позиции именно по этой бумаге на обоих счетах
        closeSpecificPosition(accountIdLong, instrument.figi());
        closeSpecificPosition(accountIdShort, instrument.figi());
    }

    private void closeSpecificPosition(String accountId, String figi) {
        var portfolio = getPortfolio(accountId);
        portfolio.getPositionsList().stream()
                .filter(p -> p.getFigi().equals(figi))
                .forEach(p -> {
                    double qty = candleToDouble(p.getQuantity());
                    if (qty != 0) {
                        OrderDirection dir = qty > 0 ? OrderDirection.ORDER_DIRECTION_SELL : OrderDirection.ORDER_DIRECTION_BUY;
                        closePositionWithResponse(accountId, figi, (long) Math.abs(qty), dir);
                    }
                });
    }
}
