package dev.kefir.service;

import com.google.protobuf.Timestamp;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tinkoff.piapi.contract.v1.*;

import java.time.Instant;
import java.util.UUID;

public class TinkoffApiService {
    private static final Logger logger = LoggerFactory.getLogger(TinkoffApiService.class);

    private final SandboxServiceGrpc.SandboxServiceBlockingStub sandboxStub;
    private final OrdersServiceGrpc.OrdersServiceBlockingStub ordersStub;
    private final OperationsServiceGrpc.OperationsServiceBlockingStub operationsStub;
    private final MarketDataServiceGrpc.MarketDataServiceBlockingStub marketDataStub;
    private final UsersServiceGrpc.UsersServiceBlockingStub userStub;
    private final boolean isSandbox;

    public TinkoffApiService(SandboxServiceGrpc.SandboxServiceBlockingStub sandboxStub,
                             OrdersServiceGrpc.OrdersServiceBlockingStub ordersStub,
                             OperationsServiceGrpc.OperationsServiceBlockingStub operationsStub,
                             MarketDataServiceGrpc.MarketDataServiceBlockingStub marketDataStub,
                             UsersServiceGrpc.UsersServiceBlockingStub userStub,
                             boolean isSandbox) {
        this.sandboxStub = sandboxStub;
        this.ordersStub = ordersStub;
        this.operationsStub = operationsStub;
        this.marketDataStub = marketDataStub;
        this.userStub = userStub;
        this.isSandbox = isSandbox;
    }

    // Универсальный метод закрытия позиции
    public PostOrderResponse closePosition(String accountId, String figi, long quantity, OrderDirection direction) {
        return executeWithRetry(() -> {
            PostOrderRequest request = PostOrderRequest.newBuilder()
                    .setFigi(figi)
                    .setQuantity(quantity)
                    .setDirection(direction)
                    .setOrderId(UUID.randomUUID().toString())
                    .setOrderType(OrderType.ORDER_TYPE_MARKET)
                    .setAccountId(accountId)
                    .build();

            return isSandbox ? sandboxStub.postSandboxOrder(request) : ordersStub.postOrder(request);
        }, "Закрытие позиции " + figi);
    }

    public PostOrderResponse postOrder(String accountId, String figi, long quantity, OrderDirection direction) {
        return executeWithRetry(() -> {
            PostOrderRequest request = PostOrderRequest.newBuilder()
                    .setFigi(figi)
                    .setQuantity(quantity)
                    .setDirection(direction)
                    .setOrderId(UUID.randomUUID().toString())
                    .setOrderType(OrderType.ORDER_TYPE_MARKET)
                    .setAccountId(accountId)
                    .build();

            return isSandbox ? sandboxStub.postSandboxOrder(request) : ordersStub.postOrder(request);
        }, "Размещение ордера " + figi);
    }

    // Получение портфеля
    public PortfolioResponse getPortfolio(String accountId) {
        PortfolioRequest request = PortfolioRequest.newBuilder().setAccountId(accountId).build();
        return isSandbox ? sandboxStub.getSandboxPortfolio(request) : operationsStub.getPortfolio(request);
    }

    // Получение свечей для ATR и уровней
    public GetCandlesResponse getCandles(String figi, com.google.protobuf.Timestamp from, com.google.protobuf.Timestamp to) {
        return marketDataStub.getCandles(GetCandlesRequest.newBuilder()
                .setFigi(figi)
                .setFrom(from)
                .setTo(to)
                .setInterval(CandleInterval.CANDLE_INTERVAL_5_MIN)
                .build());
    }

    // Логика ретраев (вынесенная из бота)
    private <T> T executeWithRetry(ApiCallable<T> callable, String actionName) {
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            try {
                return callable.call();
            } catch (StatusRuntimeException e) {
                logger.error("Ошибка API ({}) попытка {}: {}", actionName, i + 1, e.getStatus());
                if (i == maxRetries - 1) throw e;
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface ApiCallable<T> {
        T call();
    }

    public OperationsResponse getOperations(String accountId, String figi, Instant from, Instant to) {
        OperationsRequest request = OperationsRequest.newBuilder()
                .setAccountId(accountId)
                .setFrom(Timestamp.newBuilder().setSeconds(from.getEpochSecond()).setNanos(from.getNano()).build())
                .setTo(Timestamp.newBuilder().setSeconds(to.getEpochSecond()).setNanos(to.getNano()).build())
                .setFigi(figi)
                .setState(OperationState.OPERATION_STATE_EXECUTED)
                .build();

        return isSandbox
                ? sandboxStub.getSandboxOperations(request)
                : operationsStub.getOperations(request);
    }

    public void resetSandboxBalance(String accountId) {
        if (!isSandbox) {
            logger.error("Обнуление баланса доступно только в режиме Sandbox!");
            return;
        }

        try {
            var portfolio = getPortfolio(accountId);
            var cash = portfolio.getTotalAmountCurrencies();

            long units = cash.getUnits();
            int nanos = cash.getNano();

            if (units > 0 || nanos > 0) {
                var withdraw = MoneyValue.newBuilder()
                        .setCurrency("rub")
                        .setUnits(-units)
                        .setNano(-nanos)
                        .build();

                sandboxStub.sandboxPayIn(SandboxPayInRequest.newBuilder()
                        .setAccountId(accountId)
                        .setAmount(withdraw)
                        .build());

                logger.info("Баланс счета {} обнулен. Списано: {} RUB", accountId, (units + nanos / 1_000_000_000.0));
            }
        } catch (Exception e) {
            logger.error("Ошибка при обнулении баланса Sandbox счета {}: {}", accountId, e.getMessage());
        }
    }

    public void ensureSandboxBalance(String accountId, long targetUnits) {
        if (!isSandbox) return;
        try {
            var portfolio = getPortfolio(accountId);
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

                logger.info("Баланс счета {} пополнен на {} RUB.", accountId, diff);
            }
        } catch (Exception e) {
            logger.error("Ошибка пополнения баланса: {}", e.getMessage());
        }
    }

    public String[] setupSandboxAccounts() {
        if (!isSandbox) return null;
        try {
            var accounts = sandboxStub.getSandboxAccounts(GetAccountsRequest.newBuilder().build()).getAccountsList();
            String accLong, accShort;

            if (accounts.size() >= 2) {
                accLong = accounts.get(0).getId();
                accShort = accounts.get(1).getId();
            } else {
                accLong = sandboxStub.openSandboxAccount(OpenSandboxAccountRequest.newBuilder().build()).getAccountId();
                accShort = sandboxStub.openSandboxAccount(OpenSandboxAccountRequest.newBuilder().build()).getAccountId();
            }
            return new String[]{accLong, accShort};
        } catch (Exception e) {
            logger.error("Ошибка создания счетов: {}", e.getMessage());
            return null;
        }
    }

    public GetAccountsResponse getAccounts() {
        return userStub.getAccounts(GetAccountsRequest.newBuilder().build());
    }
}
