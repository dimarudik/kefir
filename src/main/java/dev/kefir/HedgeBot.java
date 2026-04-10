package dev.kefir;

import com.google.protobuf.Timestamp;
import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import ru.tinkoff.piapi.contract.v1.*;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class HedgeBot {
    private final OrdersServiceGrpc.OrdersServiceBlockingStub ordersStub;
    private boolean isLocked = true;
    private double resistanceLevel = 0;
    private double supportLevel = 0;
    private final String accountIdLong;
    private final String accountIdShort;
    private String currentFigi;
    private long currentQuantity;
    private double trailingStopPrice = 0;
    private double lastAtr = 0;
    private boolean isLongActive = false;
    private boolean isShortActive = false;
    private final MarketDataServiceGrpc.MarketDataServiceBlockingStub marketDataBlockingStub;
    private final MarketDataStreamServiceGrpc.MarketDataStreamServiceStub marketDataAsyncStub;



    public HedgeBot(String token, String accountIdLong, String accountIdShort) {
        this.accountIdLong = accountIdLong;
        this.accountIdShort = accountIdShort;
        ManagedChannel channel = ManagedChannelBuilder.forAddress("invest-public-api.tinkoff.ru", 443)
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
        this.marketDataAsyncStub = MarketDataStreamServiceGrpc.newStub(channel).withCallCredentials(credentials);
        this.marketDataBlockingStub = MarketDataServiceGrpc.newBlockingStub(channel).withCallCredentials(credentials);
    }

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

        System.out.println("Отправка одновременных заявок...");

        // Асинхронный запуск в два потока
        var task1 = java.util.concurrent.CompletableFuture.runAsync(() -> ordersStub.postOrder(longRequest));
        var task2 = java.util.concurrent.CompletableFuture.runAsync(() -> ordersStub.postOrder(shortRequest));

        java.util.concurrent.CompletableFuture.allOf(task1, task2).join();
        System.out.println("Замок успешно открыт.");
    }

    public void subscribeCandles(String figi) {
        StreamObserver<MarketDataResponse> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(MarketDataResponse response) {
                if (response.hasCandle()) {
                    processCandle(response.getCandle());
                }
            }
            @Override public void onError(Throwable t) { t.printStackTrace(); }
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

    private void processCandle(ru.tinkoff.piapi.contract.v1.Candle candle) {
        double closePrice = candleToDouble(candle.getClose());
        System.out.println("Анализ свечи. Close: " + closePrice + " | Текущий стоп: " + trailingStopPrice);

        if (isLocked) {
            // --- ЛОГИКА ВЫХОДА ИЗ ЗАМКА ---
            if (closePrice > resistanceLevel) {
                System.out.println(">>> ПРОБОЙ ВВЕРХ! Закрываем убыточный SHORT");
                closePosition(accountIdShort, currentFigi, currentQuantity, OrderDirection.ORDER_DIRECTION_BUY);

                isLocked = false;
                isLongActive = true;
                // Ставим начальный стоп ниже цены пробоя
                trailingStopPrice = closePrice - (lastAtr * 2);
                System.out.println("Активирован режим LONG. Начальный стоп: " + trailingStopPrice);

            } else if (closePrice < supportLevel) {
                System.out.println(">>> ПРОБОЙ ВНИЗ! Закрываем убыточный LONG");
                closePosition(accountIdLong, currentFigi, currentQuantity, OrderDirection.ORDER_DIRECTION_SELL);

                isLocked = false;
                isShortActive = true;
                // Ставим начальный стоп выше цены пробоя
                trailingStopPrice = closePrice + (lastAtr * 2);
                System.out.println("Активирован режим SHORT. Начальный стоп: " + trailingStopPrice);
            }
        } else {
            // --- ЛОГИКА СОПРОВОЖДЕНИЯ ПРИБЫЛИ (TRAILING STOP) ---
            if (isLongActive) {
                double potentialStop = closePrice - (lastAtr * 1.5);
                // Тянем стоп только вверх
                if (potentialStop > trailingStopPrice) {
                    trailingStopPrice = potentialStop;
                    System.out.println("Подтягиваем стоп вверх: " + trailingStopPrice);
                }
                // Проверка срабатывания
                if (closePrice <= trailingStopPrice) {
                    System.out.println("!!! ТРЕЙЛИНГ-СТОП (LONG) СРАБОТАЛ !!!");
                    closePosition(accountIdLong, currentFigi, currentQuantity, OrderDirection.ORDER_DIRECTION_SELL);
                    CompletableFuture.runAsync(this::resetCycle);
                }

            } else if (isShortActive) {
                double potentialStop = closePrice + (lastAtr * 1.5);
                // Тянем стоп только вниз
                if (potentialStop < trailingStopPrice) {
                    trailingStopPrice = potentialStop;
                    System.out.println("Подтягиваем стоп вниз: " + trailingStopPrice);
                }
                // Проверка срабатывания
                if (closePrice >= trailingStopPrice) {
                    System.out.println("!!! ТРЕЙЛИНГ-СТОП (SHORT) СРАБОТАЛ !!!");
                    closePosition(accountIdShort, currentFigi, currentQuantity, OrderDirection.ORDER_DIRECTION_BUY);
                    CompletableFuture.runAsync(this::resetCycle);
                }
            }
        }
    }

    private void resetCycle() {
        System.out.println("--- ЗАВЕРШЕНИЕ СДЕЛКИ: СБРОС СОСТОЯНИЯ ---");
        this.isLongActive = false;
        this.isShortActive = false;
        this.trailingStopPrice = 0;

        try {
            // Пауза 1 минута, чтобы не войти на той же свече
            System.out.println("Пауза перед новым циклом (60 сек)...");
            Thread.sleep(60000);

            // Обновляем волатильность и уровни перед новым входом
            initLevels(currentFigi);

            // Входим в новый замок
            openHedge(currentFigi, currentQuantity, accountIdLong, accountIdShort);

            // Возвращаем флаг готовности к анализу
            this.isLocked = true;
            System.out.println("--- НОВЫЙ ЦИКЛ ЗАПУЩЕН ---");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Ошибка паузы цикла: " + e.getMessage());
        }
    }

    // Утилита для конвертации Quotation в double
    private double candleToDouble(Quotation q) {
        return q.getUnits() + q.getNano() / 1_000_000_000.0;
    }

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
        System.out.println("Уровни установлены: Сопротивление=" + max + ", Поддержка=" + min);
    }

    private void closePosition(String accountId, String figi, long quantity, OrderDirection direction) {
        var request = PostOrderRequest.newBuilder()
                .setFigi(figi)
                .setQuantity(quantity)
                .setDirection(direction == OrderDirection.ORDER_DIRECTION_BUY ?
                        OrderDirection.ORDER_DIRECTION_SELL : OrderDirection.ORDER_DIRECTION_BUY)
                .setAccountId(accountId)
                .setOrderType(OrderType.ORDER_TYPE_MARKET)
                .setOrderId(UUID.randomUUID().toString())
                .build();

        ordersStub.postOrder(request);
        System.out.println("Позиция на счете " + accountId + " закрыта.");
    }

    private void calculateAtr(String figi) {
        // Получаем последние 14 свечей (классический период ATR)
        var response = marketDataBlockingStub.getCandles(GetCandlesRequest.newBuilder()
                .setFigi(figi)
                .setFrom(Timestamp.newBuilder().setSeconds(java.time.Instant.now().minus(2, java.time.temporal.ChronoUnit.HOURS).getEpochSecond()).build())
                .setTo(Timestamp.newBuilder().setSeconds(java.time.Instant.now().getEpochSecond()).build())
                .setInterval(CandleInterval.CANDLE_INTERVAL_5_MIN)
                .build());

        double totalRange = 0;
        for (var candle : response.getCandlesList()) {
            totalRange += (candleToDouble(candle.getHigh()) - candleToDouble(candle.getLow()));
        }
        this.lastAtr = totalRange / response.getCandlesCount();
        System.out.println("Расчитанный ATR: " + lastAtr);
    }

    private void updateAtr(String figi) {
        var now = java.time.Instant.now();
        var from = now.minus(2, java.time.temporal.ChronoUnit.HOURS);

        var response = marketDataBlockingStub.getCandles(GetCandlesRequest.newBuilder()
                .setFigi(figi)
                .setFrom(com.google.protobuf.Timestamp.newBuilder().setSeconds(from.getEpochSecond()).build())
                .setTo(com.google.protobuf.Timestamp.newBuilder().setSeconds(now.getEpochSecond()).build())
                .setInterval(CandleInterval.CANDLE_INTERVAL_5_MIN)
                .build());

        double totalRange = 0;
        for (var candle : response.getCandlesList()) {
            totalRange += (candleToDouble(candle.getHigh()) - candleToDouble(candle.getLow()));
        }
        this.lastAtr = totalRange / Math.max(1, response.getCandlesCount());
        System.out.println("Средняя волатильность (ATR): " + lastAtr);
    }
}
