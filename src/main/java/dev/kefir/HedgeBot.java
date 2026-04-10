package dev.kefir;

import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import ru.tinkoff.piapi.contract.v1.OrderDirection;
import ru.tinkoff.piapi.contract.v1.OrderType;
import ru.tinkoff.piapi.contract.v1.OrdersServiceGrpc;
import ru.tinkoff.piapi.contract.v1.PostOrderRequest;

import java.util.concurrent.Executor;

public class HedgeBot {
    private final OrdersServiceGrpc.OrdersServiceBlockingStub ordersStub;

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

}
