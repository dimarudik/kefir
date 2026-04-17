package dev.kefir;

import dev.kefir.model.BotStatus;
import dev.kefir.model.Instrument;
import dev.kefir.repository.StateRepository;
import dev.kefir.service.TinkoffApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.tinkoff.piapi.contract.v1.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class HedgeBotTest {

    private HedgeBot bot;
    private TinkoffApiService api; // Наш новый главный мок
    private StateRepository repository;
    private MarketDataStreamServiceGrpc.MarketDataStreamServiceStub marketDataStreamStub;

    @BeforeEach
    void setUp() {
        // Создаем моки сервисов
        api = Mockito.mock(TinkoffApiService.class);
        repository = Mockito.mock(StateRepository.class);
        marketDataStreamStub = Mockito.mock(MarketDataStreamServiceGrpc.MarketDataStreamServiceStub.class);

        Instrument instrument = new Instrument("SBER", "BBG004730N88", 10, 2.0, 1.8);

        bot = new HedgeBot(
                instrument,
                api,
                repository,
                marketDataStreamStub,
                "accLong",
                "accShort"
        );

        bot.setTotalProfit(0);
        bot.setStatus(BotStatus.LOCKED);
    }

    @Test
    void testTryAttachToExistingLongPosition() {
        // 1. Готовим "фейковую" позицию
        PortfolioPosition sberPosition = PortfolioPosition.newBuilder()
                .setFigi("BBG004730N88")
                .setQuantity(Quotation.newBuilder().setUnits(10).build())
                .setCurrentPrice(MoneyValue.newBuilder().setUnits(320).setNano(0).build())
                .setAveragePositionPrice(MoneyValue.newBuilder().setUnits(310).setNano(0).build())
                .build();

        // 2. Готовим ответы от нашего сервиса
        PortfolioResponse longAccountResponse = PortfolioResponse.newBuilder()
                .addAllPositions(List.of(sberPosition))
                .build();
        PortfolioResponse shortAccountResponse = PortfolioResponse.newBuilder().build();

        // Теперь настраиваем мок именно для TinkoffApiService
        when(api.getPortfolio("accLong")).thenReturn(longAccountResponse);
        when(api.getPortfolio("accShort")).thenReturn(shortAccountResponse);

        // 3. Вызываем метод
        boolean result = bot.tryAttachToExistingHedge();

        // 4. Проверки
        assertTrue(result);
        assertTrue(bot.isLongActive());
        assertEquals(BotStatus.TRAILING, bot.getStatus()); // Проверяем и новый статус
        assertEquals(310.0, bot.getLongEntryPrice());
    }

    @Test
    void testHedgeBreakoutUpward() {
        // 1. Исходное состояние
        bot.setLocked(true);
        bot.setSupportLevel(300.0);
        bot.setResistanceLevel(320.0);
        bot.setLastAtr(2.0); // ATR 2.0, Множитель 2.0 -> Стоп 4.0
        bot.setShortEntryPrice(310.0);
        bot.setLongEntryPrice(310.0);

        // 2. Мокаем ответ от нашего сервиса (исполнение по 321.0)
        PostOrderResponse fakeResponse = PostOrderResponse.newBuilder()
                .setExecutedOrderPrice(MoneyValue.newBuilder().setUnits(321).setNano(0).build())
                .build();

        // Настраиваем мок сервиса для метода postOrder
        when(api.closePosition(any(), any(), anyLong(), any())).thenReturn(fakeResponse);

        // 3. Имитируем свечу пробоя
        Candle breakoutCandle = Candle.newBuilder()
                .setClose(Quotation.newBuilder().setUnits(321).setNano(0).build())
                .build();

        bot.processCandle(breakoutCandle);

        // 4. Проверки
        assertFalse(bot.isLocked());
        assertTrue(bot.isLongActive());
        assertEquals(BotStatus.TRAILING, bot.getStatus());
        assertEquals(321.0, bot.getLastClosedPrice());

        // Проверка логики безубытка (20% ATR люфт)
        // 321 - (2.0 * 0.2) = 320.6
        assertEquals(320.6, bot.getTrailingStopPrice(), 0.001);
    }

    @Test
    void testFullCycleWithProfit() {
        // 1. Исходное состояние: замок на 310.0, уровни 300-320, ATR 2.0, Множитель 2.0
        bot.setLocked(true);
        bot.setSupportLevel(300.0);
        bot.setResistanceLevel(320.0);
        bot.setLastAtr(2.0);
        bot.setLongEntryPrice(310.0);
        bot.setShortEntryPrice(310.0);

        // Мокаем ответы через TinkoffApiService
        PostOrderResponse breakoutResp = PostOrderResponse.newBuilder()
                .setExecutedOrderPrice(MoneyValue.newBuilder().setUnits(321).setNano(0).build()).build();
        PostOrderResponse takeProfitResp = PostOrderResponse.newBuilder()
                .setExecutedOrderPrice(MoneyValue.newBuilder().setUnits(326).setNano(0).build()).build();

        // Настраиваем последовательные ответы для closePosition
        when(api.closePosition(any(), any(), anyLong(), any()))
                .thenReturn(breakoutResp)    // Вскрытие
                .thenReturn(takeProfitResp);  // Трейлинг-стоп

        // СВЕЧА 1: Пробой вверх (321.0)
        bot.processCandle(createCandle(321.0));
        assertTrue(bot.isLongActive());
        // С учетом люфта 20% от ATR 2.0 (0.4): 321.0 - 0.4 = 320.6
        assertEquals(320.6, bot.getTrailingStopPrice(), 0.001, "Стоп должен быть в безубытке с люфтом");

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
    void testNoInstantCloseOnSameCandle() {
        // 1. Настройка: Сопротивление 320, ATR 2.0
        bot.setLocked(true);
        bot.setResistanceLevel(320.0);
        bot.setLastAtr(2.0);

        // Мокаем ответ через наш новый сервис
        PostOrderResponse breakoutResponse = PostOrderResponse.newBuilder()
                .setExecutedOrderPrice(MoneyValue.newBuilder().setUnits(321).setNano(0).build())
                .build();

        // Настраиваем мок на closePosition (который вызывает бот при вскрытии замка)
        when(api.closePosition(any(), any(), anyLong(), any())).thenReturn(breakoutResponse);

        // 2. Имитируем свечу пробоя: Close = 321.0
        // Стоп рассчитается как: 321.0 - (2.0 * 0.2 люфт) = 320.6
        Candle breakoutCandle = Candle.newBuilder()
                .setClose(Quotation.newBuilder().setUnits(321).setNano(0).build())
                .build();

        bot.processCandle(breakoutCandle);

        // 3. ПРОВЕРКА
        assertTrue(bot.isLongActive(), "Позиция должна остаться открытой! 'return' прерывает метод после вскрытия.");
        assertFalse(bot.isLocked(), "Замок должен быть вскрыт");

        // Проверяем значение стопа с учетом 20% люфта
        assertEquals(320.6, bot.getTrailingStopPrice(), 0.001, "Стоп должен быть 320.6");
    }

    @Test
    void testInitialStopIsNotWorseThanBreakoutPrice() {
        // 1. Настройка: Вход 310, Сопротивление 320, ATR огромный (10.0), Множитель 2.0
        bot.setLocked(true);
        bot.setResistanceLevel(320.0);
        bot.setSupportLevel(300.0);
        bot.setLastAtr(10.0);
        bot.setLongEntryPrice(310.0);
        bot.setShortEntryPrice(310.0);

        // Мокаем исполнение через наш сервис
        PostOrderResponse breakoutResp = PostOrderResponse.newBuilder()
                .setExecutedOrderPrice(MoneyValue.newBuilder().setUnits(321).setNano(0).build())
                .build();

        // Используем api.closePosition вместо sandboxStub
        when(api.closePosition(any(), any(), anyLong(), any())).thenReturn(breakoutResp);

        // 2. Имитируем пробой свечой 321.0
        bot.processCandle(createCandle(321.0));

        // 3. ПРОВЕРКА:
        // Расчетный стоп по ATR был бы: 321 - (10 * 2) = 301.0
        // Наш "безопасный" стоп с люфтом 20%: 321 - (10 * 0.2) = 319.0
        // Так как 301.0 < 319.0, бот обязан выбрать 319.0
        assertEquals(319.0, bot.getTrailingStopPrice(), 0.001,
                "Стоп должен установиться на уровне безопасного безубытка (319.0)");

        assertTrue(bot.isLongActive());
        assertEquals(BotStatus.TRAILING, bot.getStatus());
    }
}
