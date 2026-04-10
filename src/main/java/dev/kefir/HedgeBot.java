package dev.kefir;

import com.google.protobuf.Timestamp;
import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tinkoff.piapi.contract.v1.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class HedgeBot {
    private static final Logger logger = LoggerFactory.getLogger(HedgeBot.class);

    private final boolean isSandbox;
    private final UsersServiceGrpc.UsersServiceBlockingStub userStub;
    private final OrdersServiceGrpc.OrdersServiceBlockingStub ordersStub;
    private final SandboxServiceGrpc.SandboxServiceBlockingStub sandboxStub;
    private volatile boolean isLocked = true;
    private double resistanceLevel = 0;
    private double supportLevel = 0;
    private String accountIdLong;
    private String accountIdShort;
    private String currentFigi;
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
    public HedgeBot(String token, String accountIdLong, String accountIdShort, boolean isSandbox) {
        this.isSandbox = isSandbox;
        this.accountIdLong = accountIdLong;
        this.accountIdShort = accountIdShort;
        String host = isSandbox ? "sandbox-invest-public-api.tinkoff.ru" : "invest-public-api.tinkoff.ru";

        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, 443)
                .useTransportSecurity()
                .build();

        // Создаем Credentials для автоматической авторизации
        CallCredentials credentials = new CallCredentials() {
            @Override
            public void applyRequestMetadata(RequestInfo requestInfo, Executor executor, MetadataApplier metadataApplier) {
                executor.execute(() -> {
                    try {
                        io.grpc.Metadata headers = new io.grpc.Metadata();
                        io.grpc.Metadata.Key<String> authKey = io.grpc.Metadata.Key.of("Authorization", io.grpc.Metadata.ASCII_STRING_MARSHALLER);
                        headers.put(authKey, "Bearer " + token);
                        metadataApplier.apply(headers);
                    } catch (Throwable e) {
                        metadataApplier.fail(io.grpc.Status.UNAUTHENTICATED.withCause(e));
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
     * @param figi Идентификатор финансового инструмента
     * @param quantity Количество лотов для каждой позиции
     * @param accLong ID счета для покупки
     * @param accShort ID счета для продажи
     */
    public void openHedge(String figi, long quantity, String accLong, String accShort) {
        this.currentFigi = figi;
        this.currentQuantity = quantity;
        var longRequest = PostOrderRequest.newBuilder()
                .setFigi(figi)
                .setQuantity(quantity)
                .setDirection(OrderDirection.ORDER_DIRECTION_BUY)
                .setAccountId(accLong)
                .setOrderType(OrderType.ORDER_TYPE_MARKET)
                .setOrderId(java.util.UUID.randomUUID().toString())
                .build();

        var shortRequest = PostOrderRequest.newBuilder()
                .setFigi(figi)
                .setQuantity(quantity)
                .setDirection(OrderDirection.ORDER_DIRECTION_SELL)
                .setAccountId(accShort)
                .setOrderType(OrderType.ORDER_TYPE_MARKET)
                .setOrderId(java.util.UUID.randomUUID().toString())
                .build();

        logger.info("Отправка одновременных заявок...");

        // Асинхронный запуск в два потока
        var task1 = java.util.concurrent.CompletableFuture.runAsync(() -> ordersStub.postOrder(longRequest));
        var task2 = java.util.concurrent.CompletableFuture.runAsync(() -> ordersStub.postOrder(shortRequest));

        java.util.concurrent.CompletableFuture.allOf(task1, task2).join();
        logger.info("Замок успешно открыт.");
    }

    /**
     * Подписывается на асинхронный стрим рыночных данных для получения 5-минутных свечей.
     * При получении новой свечи данные передаются в метод {@link #processCandle(Candle)}.
     *
     * @param figi Идентификатор финансового инструмента
     */
    public void subscribeCandles(String figi) {
        StreamObserver<MarketDataResponse> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(MarketDataResponse response) {
                if (response.hasCandle()) {
                    processCandle(response.getCandle());
                }
            }
            @Override public void onError(Throwable t) {
                logger.error("Ошибка при получении данных: {}", t.getMessage());
            }
            @Override public void onCompleted() { }
        };

        StreamObserver<MarketDataRequest> requestObserver = marketDataAsyncStub.marketDataStream(responseObserver);

        requestObserver.onNext(MarketDataRequest.newBuilder()
                .setSubscribeCandlesRequest(SubscribeCandlesRequest.newBuilder()
                        .setSubscriptionAction(SubscriptionAction.SUBSCRIPTION_ACTION_SUBSCRIBE)
                        .addInstruments(CandleInstrument.newBuilder()
                                .setFigi(figi)
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
    private void processCandle(ru.tinkoff.piapi.contract.v1.Candle candle) {
        double closePrice = candleToDouble(candle.getClose());
        logger.info("Анализ свечи. Close: {} | Текущий стоп: {}", closePrice, trailingStopPrice);

        if (isLocked) {
            // --- ЛОГИКА ВЫХОДА ИЗ ЗАМКА ---
            if (closePrice > resistanceLevel) {
                logger.warn(">>> ПРОБОЙ ВВЕРХ на FIGI: {}. Закрываем убыточный SHORT", currentFigi);
                closePosition(accountIdShort, currentFigi, currentQuantity, OrderDirection.ORDER_DIRECTION_BUY);

                isLocked = false;
                isLongActive = true;
                // Ставим начальный стоп ниже цены пробоя
                trailingStopPrice = closePrice - (lastAtr * 2);
                logger.info("Активирован режим LONG. Начальный стоп: {}", trailingStopPrice);

            } else if (closePrice < supportLevel) {
                logger.warn(">>> ПРОБОЙ ВНИЗ на FIGI: {}. Закрываем убыточный LONG", currentFigi);
                closePosition(accountIdLong, currentFigi, currentQuantity, OrderDirection.ORDER_DIRECTION_SELL);

                isLocked = false;
                isShortActive = true;
                // Ставим начальный стоп выше цены пробоя
                trailingStopPrice = closePrice + (lastAtr * 2);
                logger.info("Активирован режим SHORT. Начальный стоп: {}", trailingStopPrice);
            }

        } else {
            // --- ЛОГИКА СОПРОВОЖДЕНИЯ ПРИБЫЛИ (TRAILING STOP) ---
            if (isLongActive) {
                double potentialStop = closePrice - (lastAtr * 1.5);
                // Тянем стоп только вверх
                if (potentialStop > trailingStopPrice) {
                    trailingStopPrice = potentialStop;
                    logger.info("Подтягиваем стоп вверх: {}", trailingStopPrice);
                }
                // Проверка срабатывания
                if (closePrice <= trailingStopPrice) {
                    logger.warn("!!! ТРЕЙЛИНГ-СТОП (LONG) СРАБОТАЛ !!!");
                    closePosition(accountIdLong, currentFigi, currentQuantity, OrderDirection.ORDER_DIRECTION_SELL);
                    CompletableFuture.runAsync(this::resetCycle);
                }

            } else if (isShortActive) {
                double potentialStop = closePrice + (lastAtr * 1.5);
                // Тянем стоп только вниз
                if (potentialStop < trailingStopPrice) {
                    trailingStopPrice = potentialStop;
                    logger.info("Подтягиваем стоп вниз: {}", trailingStopPrice);
                }
                // Проверка срабатывания
                if (closePrice >= trailingStopPrice) {
                    logger.warn("!!! ТРЕЙЛИНГ-СТОП (SHORT) СРАБОТАЛ !!!");
                    closePosition(accountIdShort, currentFigi, currentQuantity, OrderDirection.ORDER_DIRECTION_BUY);
                    CompletableFuture.runAsync(this::resetCycle);
                }
            }
        }
    }

    /**
     * Выполняет полный сброс состояния робота и подготовку к новому циклу.
     * Включает минутную паузу, перерасчет уровней и открытие нового "замка".
     * Запускается асинхронно, чтобы не блокировать поток рыночных данных.
     */
    private void resetCycle() {
        logger.info("--- ЗАВЕРШЕНИЕ СДЕЛКИ: СБРОС СОСТОЯНИЯ ---");
        this.isLongActive = false;
        this.isShortActive = false;
        this.trailingStopPrice = 0;

        try {
            // Пауза 1 минута, чтобы не войти на той же свече
            logger.info("Пауза перед новым циклом (60 сек)...");
            Thread.sleep(60000);

            // Обновляем волатильность и уровни перед новым входом
            initLevels(currentFigi);

            // Входим в новый замок
            openHedge(currentFigi, currentQuantity, accountIdLong, accountIdShort);

            // Возвращаем флаг готовности к анализу
            this.isLocked = true;
            logger.info("--- НОВЫЙ ЦИКЛ ЗАПУЩЕН ---");
        } catch (InterruptedException e) {
            logger.error("Критическая ошибка при паузе цикла", e);
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
        var now = java.time.Instant.now();
        var from = now.minus(4, java.time.temporal.ChronoUnit.HOURS);

        var response = marketDataBlockingStub.getCandles(GetCandlesRequest.newBuilder()
                .setFigi(figi)
                .setFrom(Timestamp.newBuilder().setSeconds(from.getEpochSecond()).build())
                .setTo(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build())
                .setInterval(CandleInterval.CANDLE_INTERVAL_HOUR)
                .build());

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
        logger.info("Уровни установлены: Сопротивление = {} , Поддержка= {} ", max, min);
    }

    /**
     * Отправляет рыночное поручение на закрытие текущей позиции.
     *
     * @param accountId ID счета, на котором нужно закрыть позицию
     * @param figi Идентификатор инструмента
     * @param quantity Количество лотов
     * @param direction Направление сделки (для закрытия Long — SELL, для Short — BUY)
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

        executeOrder(request);
        logger.info("Позиция на счете {} закрыта.", accountId);
    }

    /**
     * Вычисляет среднюю волатильность (ATR) инструмента за последние 14 пятиминутных свечей.
     * Значение ATR используется для расчета дистанции динамического стоп-лосса (Trailing Stop).
     */
    private void updateAtr(String figi) {
        var now = java.time.Instant.now();
        var from = now.minus(2, java.time.temporal.ChronoUnit.HOURS);

        try {
            var response = marketDataBlockingStub.getCandles(GetCandlesRequest.newBuilder()
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
            for (var candle : response.getCandlesList()) {
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
     * Подготавливает счета для работы в песочнице.
     * Проверяет наличие открытых счетов: если их нет или меньше двух — создает новые.
     * Если счета уже есть — использует первые два найденных.
     * В конце пополняет баланс обоих счетов до 100 000 виртуальных рублей.
     */
    public void prepareSandboxAccounts() {
        if (!isSandbox) {
            logger.error("Метод подготовки счетов вызван в режиме REAL. Операция отменена.");
            return;
        }

        try {
            // Запрашиваем список всех существующих аккаунтов в Sandbox
            var response = sandboxStub.getSandboxAccounts(ru.tinkoff.piapi.contract.v1.GetAccountsRequest.newBuilder().build());
            List<ru.tinkoff.piapi.contract.v1.Account> accounts = response.getAccountsList();

            if (accounts.size() >= 2) {
                this.accountIdLong = accounts.get(0).getId();
                this.accountIdShort = accounts.get(1).getId();
                logger.info("Используем существующие счета Sandbox: Long={}, Short={}", accountIdLong, accountIdShort);
            } else {
                logger.info("Счетов в Sandbox недостаточно (найдено: {}). Создаем новые...", accounts.size());
                this.accountIdLong = sandboxStub.openSandboxAccount(ru.tinkoff.piapi.contract.v1.OpenSandboxAccountRequest.newBuilder().build()).getAccountId();
                this.accountIdShort = sandboxStub.openSandboxAccount(ru.tinkoff.piapi.contract.v1.OpenSandboxAccountRequest.newBuilder().build()).getAccountId();
            }

            // Пополняем счета для гарантии наличия средств на сделки
            var deposit = ru.tinkoff.piapi.contract.v1.MoneyValue.newBuilder()
                    .setCurrency("rub")
                    .setUnits(100_000)
                    .build();

            sandboxStub.sandboxPayIn(ru.tinkoff.piapi.contract.v1.SandboxPayInRequest.newBuilder().setAccountId(accountIdLong).setAmount(deposit).build());
            sandboxStub.sandboxPayIn(ru.tinkoff.piapi.contract.v1.SandboxPayInRequest.newBuilder().setAccountId(accountIdShort).setAmount(deposit).build());

            logger.info("Счета в Sandbox пополнены на 100 000 RUB каждый.");
        } catch (Exception e) {
            logger.error("Критическая ошибка при подготовке Sandbox счетов", e);
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
}
