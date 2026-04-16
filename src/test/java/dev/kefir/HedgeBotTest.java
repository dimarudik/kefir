package dev.kefir;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import ru.tinkoff.piapi.contract.v1.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class HedgeBotTest {

    private HedgeBot bot;
    private SandboxServiceGrpc.SandboxServiceBlockingStub sandboxStub;
    // Остальные стабы нам понадобятся для конструктора, но в этом тесте они не участвуют
    private MarketDataServiceGrpc.MarketDataServiceBlockingStub marketDataBlockingStub;
    private MarketDataStreamServiceGrpc.MarketDataStreamServiceStub marketDataStreamStub;
    private OrdersServiceGrpc.OrdersServiceBlockingStub ordersStub;
    private OperationsServiceGrpc.OperationsServiceBlockingStub operationsStub;

    @BeforeEach
    void setUp() {
        // Создаем моки для всех gRPC сервисов
        sandboxStub = Mockito.mock(SandboxServiceGrpc.SandboxServiceBlockingStub.class);
        marketDataBlockingStub = Mockito.mock(MarketDataServiceGrpc.MarketDataServiceBlockingStub.class);
        marketDataStreamStub = Mockito.mock(MarketDataStreamServiceGrpc.MarketDataStreamServiceStub.class);
        ordersStub = Mockito.mock(OrdersServiceGrpc.OrdersServiceBlockingStub.class);
        operationsStub = Mockito.mock(OperationsServiceGrpc.OperationsServiceBlockingStub.class);
        marketDataStreamStub = Mockito.mock(MarketDataStreamServiceGrpc.MarketDataStreamServiceStub.class);

        Instrument instrument = new Instrument("SBER", "BBG004730N88", 10, 2.0);

        // Инициализируем бота с моками
        bot = new HedgeBot(
                instrument,
                sandboxStub,
                marketDataBlockingStub,
                marketDataStreamStub,
                ordersStub,
                operationsStub,
                "accLong",
                "accShort",
                true);

        bot.setTotalProfit(0);
        bot.setStatus(BotStatus.LOCKED);
    }

    @Test
    void testTryAttachToExistingLongPosition() {
        // 1. Готовим "фейковую" позицию Long по Сберу (10 акций)
        PortfolioPosition sberPosition = PortfolioPosition.newBuilder()
                .setFigi("BBG004730N88")
                .setQuantity(Quotation.newBuilder().setUnits(10).build()) // Положительное число = Long
                .setCurrentPrice(MoneyValue.newBuilder().setUnits(320).setNano(0).build())
                .setAveragePositionPrice(MoneyValue.newBuilder().setUnits(310).setNano(0).build())
                .build();

        // 2. Имитируем ответ от SandboxService (пустой портфель для Short-счета, SBER для Long-счета)
        PortfolioResponse longAccountResponse = PortfolioResponse.newBuilder()
                .addAllPositions(List.of(sberPosition))
                .build();

        PortfolioResponse shortAccountResponse = PortfolioResponse.newBuilder().build(); // Пусто

        // Настраиваем мок: на любой запрос портфеля возвращаем наши заготовки
        when(sandboxStub.getSandboxPortfolio(any()))
                .thenReturn(longAccountResponse)   // Первый вызов (для Long счета)
                .thenReturn(shortAccountResponse); // Второй вызов (для Short счета)

        // 3. Вызываем тестируемый метод
        boolean result = bot.tryAttachToExistingHedge();

        // 4. Проверяем результат
        assertTrue(result, "Бот должен успешно подхватить позицию");
        assertTrue(bot.isLongActive(), "Должен активироваться режим LONG");
        assertFalse(bot.isLocked(), "Замок не должен быть активен (так как шорта нет)");
        assertEquals(310.0, bot.getLongEntryPrice(), "Цена входа должна восстановиться из портфеля");
    }

    @Test
    void testHedgeBreakoutUpward() {
        // 1. Устанавливаем начальное состояние "в замке"
        bot.setLocked(true);
        bot.setSupportLevel(300.0);
        bot.setResistanceLevel(320.0);
        bot.setLastAtr(2.0); // ATR = 2, значит оффсет будет 4.0 (при множителе 2.0)
        bot.setShortEntryPrice(310.0);
        bot.setLongEntryPrice(310.0);

        // 2. Мокаем ответ от биржи при закрытии позиции
        // Нам нужно, чтобы closePositionWithResponse вернул "исполнение" по цене 321.0
        PostOrderResponse fakeResponse = PostOrderResponse.newBuilder()
                .setExecutedOrderPrice(MoneyValue.newBuilder().setUnits(321).setNano(0).build())
                .build();

        // Настраиваем мок (в зависимости от того, что использует ваш метод)
        when(sandboxStub.postSandboxOrder(any())).thenReturn(fakeResponse);

        // 3. Имитируем приход свечи с пробоем (Close = 321.0)
        // Создаем объект свечи через билдер gRPC
        Candle breakoutCandle = Candle.newBuilder()
                .setClose(Quotation.newBuilder().setUnits(321).setNano(0).build())
                .build();

        // 4. Вызываем обработку
        bot.processCandle(breakoutCandle);

        // 5. ПРОВЕРКИ
        assertFalse(bot.isLocked(), "Замок должен быть вскрыт");
        assertTrue(bot.isLongActive(), "Должен активироваться режим LONG");
        assertEquals(321.0, bot.getLastClosedPrice(), "Должна сохраниться цена пробоя");

        // Проверяем логику безубытка: так как стоп (321 - 4 = 317) ниже входа (321),
        // а цена Close (321) >= входа (321), стоп должен встать ровно в 321.0
        assertEquals(321.0, bot.getTrailingStopPrice(), "Стоп должен сработать в безубыток");
    }

    @Test
    void testNoInstantCloseOnSameCandle() {
        // 1. Настройка (Цена пробоя 320, ATR 2.0 -> стоп 316, но Breakeven поставит 320)
        bot.setLocked(true);
        bot.setResistanceLevel(320.0);
        bot.setLastAtr(2.0);

        // Мокаем ответ биржи: купили по 321.0
        PostOrderResponse breakoutResponse = PostOrderResponse.newBuilder()
                .setExecutedOrderPrice(MoneyValue.newBuilder().setUnits(321).setNano(0).build())
                .build();
        when(sandboxStub.postSandboxOrder(any())).thenReturn(breakoutResponse);

        // 2. Имитируем "злую" свечу: Close = 321 (пробой), но она же триггерит стоп, если нет return
        // Так как стоп встанет в 321.0 (безубыток), а цена 321.0 <= 321.0
        Candle evilCandle = Candle.newBuilder()
                .setClose(Quotation.newBuilder().setUnits(321).setNano(0).build())
                .build();

        bot.processCandle(evilCandle);

        // 3. ПРОВЕРКА
        assertTrue(bot.isLongActive(), "Позиция должна остаться открытой! 'return' должен был прервать метод.");
        assertFalse(bot.isShortActive());
        assertEquals(321.0, bot.getTrailingStopPrice());
    }

    @Test
    void testFullCycleWithProfit() {
        // 1. Исходное состояние: замок на 310.0, уровни 300-320, ATR 2.0
        bot.setLocked(true);
        bot.setSupportLevel(300.0);
        bot.setResistanceLevel(320.0);
        bot.setLastAtr(2.0);
        bot.setLongEntryPrice(310.0);
        bot.setShortEntryPrice(310.0);

        // Мокаем ответы биржи: вскрытие по 321, фиксация прибыли по 326
        PostOrderResponse breakoutResp = PostOrderResponse.newBuilder()
                .setExecutedOrderPrice(MoneyValue.newBuilder().setUnits(321).setNano(0).build()).build();
        PostOrderResponse takeProfitResp = PostOrderResponse.newBuilder()
                .setExecutedOrderPrice(MoneyValue.newBuilder().setUnits(326).setNano(0).build()).build();

        when(sandboxStub.postSandboxOrder(any()))
                .thenReturn(breakoutResp)    // Первый вызов: вскрытие замка
                .thenReturn(takeProfitResp);  // Второй вызов: закрытие по трейлингу

        // СВЕЧА 1: Пробой вверх (321.0)
        bot.processCandle(createCandle(321.0));
        assertTrue(bot.isLongActive());
        assertEquals(321.0, bot.getTrailingStopPrice(), "Стоп должен быть в безубытке (321.0)");

        // СВЕЧА 2: Цена улетает вверх (330.0). Расчетный стоп: 330 - (2 * 2.0) = 326.0
        bot.processCandle(createCandle(330.0));
        assertEquals(326.0, bot.getTrailingStopPrice(), "Стоп должен подтянуться до 326.0");

        // СВЕЧА 3: Микро-откат (328.0). Стоп не должен двигаться вниз!
        bot.processCandle(createCandle(328.0));
        assertEquals(326.0, bot.getTrailingStopPrice(), "Стоп должен остаться на 326.0");

        // СВЕЧА 4: Удар в стоп (325.0). Закрытие цикла.
        bot.processCandle(createCandle(325.0));

        // 2. ФИНАЛЬНАЯ ПРОВЕРКА МАТЕМАТИКИ
        // Убыток по Short: (310.0 - 321.0) = -11.0
        // Прибыль по Long: (326.0 - 310.0) = +16.0
        // Итого профит: +5.0
        assertFalse(bot.isLongActive(), "Цикл должен быть завершен");
        assertEquals(5.0, bot.getTotalProfit(), 0.001, "Общий профит должен быть ровно 5.0");
    }

    // Вспомогательный метод для чистоты кода тестов
    private Candle createCandle(double price) {
        return Candle.newBuilder()
                .setClose(Quotation.newBuilder().setUnits((long)price).setNano(0).build())
                .build();
    }

    @Test
    void testInitialStopIsNotWorseThanBreakoutPrice() {
        // 1. Настройка: Вход 310, Сопротивление 320, ATR огромный (10.0)
        bot.setLocked(true);
        bot.setResistanceLevel(320.0);
        bot.setLastAtr(10.0); // 2 * ATR = 20.0. Обычный стоп был бы 321 - 20 = 301
        bot.setLongEntryPrice(310.0);
        bot.setShortEntryPrice(310.0);

        // Мокаем исполнение пробоя по 321.0
        PostOrderResponse breakoutResp = PostOrderResponse.newBuilder()
                .setExecutedOrderPrice(MoneyValue.newBuilder().setUnits(321).setNano(0).build())
                .build();
        when(sandboxStub.postSandboxOrder(any())).thenReturn(breakoutResp);

        // 2. Имитируем пробой свечой 321.0
        bot.processCandle(createCandle(321.0));

        // 3. ПРОВЕРКА:
        // Без твоей правки стоп был бы 301.0 (убыток -9 рублей по кругу)
        // С твоей правкой стоп обязан стать 321.0 (безубыток 0.0 рублей по кругу)
        assertEquals(321.0, bot.getTrailingStopPrice(),
                "Начальный стоп должен быть равен цене пробоя (безубытку), если расчетный стоп по ATR слишком низко");

        assertTrue(bot.isLongActive());
    }
}
