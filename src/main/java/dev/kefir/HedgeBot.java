package dev.kefir;

import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import ru.tinkoff.piapi.contract.v1.*;

import java.util.concurrent.Executor;

public class HedgeBot {
    private final OrdersServiceGrpc.OrdersServiceBlockingStub ordersStub;
    private final MarketDataServiceGrpc.MarketDataServiceStub marketDataStub;
    private boolean isLocked = true;

    public HedgeBot(String token) {
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
        this.marketDataStub = MarketDataServiceGrpc.newStub(channel).withCallCredentials(credentials);
    }

    public void openHedge(String figi, long quantity, String accLong, String accShort) {
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
                    var candle = response.getCandle();
                    processCandle(candle);
                }
            }

            @Override
            public void onError(Throwable t) {
                System.err.println("Ошибка стрима: " + t.getMessage());
                // Здесь стоит добавить логику переподключения
            }

            @Override
            public void onCompleted() {
                System.out.println("Стрим завершен");
            }
        };

        // Открываем стрим
        StreamObserver<MarketDataRequest> requestObserver = marketDataStub.marketDataStream(responseObserver);

        // Отправляем запрос на подписку
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

    private void processCandle(Candle candle) {
        double closePrice = candleToDouble(candle.getClose());
        System.out.println("Новая свеча закрыта: " + closePrice);

        if (isLocked) {
            // 1. Проверяем уровни (уровни нужно инициализировать заранее)
            if (closePrice > resistanceLevel) {
                System.out.println("Пробой вверх! Закрываем SHORT");
                closePosition(accountIdShort);
                isLocked = false;
                startTrailingStop(accountIdLong);
            } else if (closePrice < supportLevel) {
                System.out.println("Пробой вниз! Закрываем LONG");
                closePosition(accountIdLong);
                isLocked = false;
                startTrailingStop(accountIdShort);
            }
        }
    }

    // Утилита для конвертации Quotation в double
    private double candleToDouble(Quotation q) {
        return q.getUnits() + q.getNano() / 1_000_000_000.0;
    }
}
