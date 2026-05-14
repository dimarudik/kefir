package dev.kefir.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer; // СТРОГО ЭТОТ ИМПОРТ
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class TelegramNotificationService implements LongPollingUpdateConsumer { // ПРАВИЛЬНЫЙ ИНТЕРФЕЙС
    private static final Logger logger = LoggerFactory.getLogger(TelegramNotificationService.class);

    private final TelegramClient telegramClient;
    private final String chatId;
    private final boolean isEnabled;

    // Реестр слушателей для параллельной работы нескольких ботов на общих счетах
    private final List<Consumer<String>> callbackHandlers = new CopyOnWriteArrayList<>();

    public TelegramNotificationService(String token, String chatId) {
        if (token == null || token.isEmpty() || chatId == null || chatId.isEmpty()) {
            this.telegramClient = null;
            this.chatId = null;
            this.isEnabled = false;
            return;
        }
        this.telegramClient = new OkHttpTelegramClient(token);
        this.chatId = chatId;
        this.isEnabled = true;

        // Регистрация Long Polling сессии под архитектуру 9.x
        try {
            TelegramBotsLongPollingApplication botsApplication = new TelegramBotsLongPollingApplication();
            botsApplication.registerBot(token, this);
            logger.info("Движок Telegram Long Polling успешно зарегистрирован.");
        } catch (Exception e) {
            logger.error("Ошибка при инициализации LongPolling сессии: {}", e.getMessage());
        }
    }

    public void addCallbackHandler(Consumer<String> handler) {
        this.callbackHandlers.add(handler);
    }

    public void sendBreakoutAlert(String ticker, String text) {
        if (!isEnabled) return;
        CompletableFuture.runAsync(() -> {
            try {
                InlineKeyboardButton closeButton = InlineKeyboardButton.builder()
                        .text("🚨 Закрыть " + ticker + " вручную")
                        .callbackData("FORCE_CLOSE_" + ticker)
                        .build();

                InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                        .keyboardRow(new InlineKeyboardRow(closeButton))
                        .build();

                SendMessage message = SendMessage.builder()
                        .chatId(chatId)
                        .text(text)
                        .parseMode("Markdown")
                        .replyMarkup(keyboard)
                        .build();
                telegramClient.execute(message);
            } catch (Exception e) {
                logger.error("Ошибка отправки интерактивного алерта для [{}]: {}", ticker, e.getMessage());
            }
        });
    }

    public void sendNotification(String text) {
        if (!isEnabled) return;

        CompletableFuture.runAsync(() -> {
            try {
                SendMessage message = SendMessage.builder()
                        .chatId(chatId)
                        .text(text)
                        .parseMode("Markdown")
                        .build();
                telegramClient.execute(message);
                logger.info("[TG] Системное уведомление успешно отправлено в чат."); // Добавь для контроля
            } catch (Exception e) {
                logger.error("Ошибка отправки сообщения в Telegram: {}", e.getMessage());
            }
        });
    }

    // ТОЧНЫЙ КОНТРАКТ ДЛЯ ВЕРСИИ 9.6.0 И СУПЕРТИПА LongPollingUpdateConsumer
    @Override
    public void consume(List<Update> updates) {
        for (Update update : updates) {
            // 1. ПЕРЕХВАТ ОБЫЧНОГО ТЕКСТА (Для теста связи)
            if (update.hasMessage() && update.getMessage().hasText()) {
                String userText = update.getMessage().getText();
                logger.info("[TG TEST] Получено текстовое сообщение из Telegram: '{}'", userText);

                if (userText.equalsIgnoreCase("/status")) {
                    sendNotification("📊 _Запрос статуса принят. Система работает в штатном режиме._");
                }
            }

            // 2. ПЕРЕХВАТ НАЖАТИЯ КНОПОК (Наша основная логика)
            if (update.hasCallbackQuery()) {
                String data = update.getCallbackQuery().getData();
                logger.info("[TG COMMAND] Нажата кнопка в Telegram: {}", data);

                for (Consumer<String> handler : callbackHandlers) {
                    CompletableFuture.runAsync(() -> handler.accept(data));
                }
                sendNotification("⏳ _Запрос на экстренное ручное закрытие передан в логическое ядро..._");
            }
        }
    }
}
