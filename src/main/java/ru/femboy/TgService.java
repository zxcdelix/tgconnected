package ru.femboy;

import it.tdlight.client.*;
import it.tdlight.jni.TdApi;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class TgService {
    private static final SimpleTelegramClientFactory FACTORY = new SimpleTelegramClientFactory();

    private final TgConfig config;
    private SimpleTelegramClient client;

    private volatile CompletableFuture<String> codeFuture;
    private volatile CompletableFuture<String> passwordFuture;

    private volatile long lastChatId = 0;
    private volatile boolean authorized = false;
    private final Map<String, Long> usernameCache = new ConcurrentHashMap<>();
    private final List<Component> pending = new CopyOnWriteArrayList<>();

    public TgService(TgConfig config) {
        this.config = config;
    }

    // ---------- вывод в чат ----------

    public void say(Component c) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendSystemMessage(c);
            } else {
                pending.add(c);
            }
        });
    }

    public void flushPending() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        for (Component c : pending) mc.player.sendSystemMessage(c);
        pending.clear();
    }

    private MutableComponent tag() {
        return Component.literal("[TG] ").withStyle(ChatFormatting.AQUA);
    }

    public void info(String s) {
        say(tag().append(Component.literal(s).withStyle(ChatFormatting.GRAY)));
    }

    public void error(String s) {
        say(tag().append(Component.literal(s).withStyle(ChatFormatting.RED)));
    }

    // ---------- запуск / вход ----------

    public boolean hasSession() {
        return Files.exists(TgConfig.dir().resolve("session").resolve("data"));
    }

    public void autoStart() {
        if (hasSession() && !config.phone.isBlank()) {
            start(AuthenticationSupplier.user(config.phone));
        }
    }

    public void loginPhone(String phone) {
        config.phone = phone;
        config.save();
        start(AuthenticationSupplier.user(phone));
    }

    public void loginQr() {
        start(AuthenticationSupplier.qrCode());
    }

    private synchronized void start(AuthenticationSupplier<?> auth) {
        stop();
        try {
            APIToken token = config.apiId != 0 && !config.apiHash.isBlank()
                    ? new APIToken(config.apiId, config.apiHash)
                    : APIToken.example();

            TDLibSettings settings = TDLibSettings.create(token);
            Path session = TgConfig.dir().resolve("session");
            Files.createDirectories(session);
            settings.setDatabaseDirectoryPath(session.resolve("data"));
            settings.setDownloadedFilesDirectoryPath(session.resolve("downloads"));

            SimpleTelegramClientBuilder builder = FACTORY.builder(settings);
            builder.setClientInteraction(this::onParameterRequest);
            builder.addUpdateHandler(TdApi.UpdateAuthorizationState.class, this::onAuthState);
            builder.addUpdateHandler(TdApi.UpdateNewMessage.class, this::onNewMessage);

            client = builder.build(auth);
            info("Подключение к Telegram...");
        } catch (Throwable t) {
            error("Не удалось запустить клиент: " + t);
        }
    }

    public void stop() {
        if (client != null) {
            try { client.close(); } catch (Throwable ignored) {}
            client = null;
        }
        authorized = false;
    }

    public void logout() {
        if (client == null) {
            error("Ты не в системе.");
            return;
        }
        client.send(new TdApi.LogOut());
        config.phone = "";
        config.save();
        authorized = false;
        info("Выход выполнен.");
    }

    public void submitCode(String code) {
        CompletableFuture<String> f = codeFuture;
        if (f != null && !f.isDone()) f.complete(code); else error("Сейчас код не запрашивался.");
    }

    public void submitPassword(String pass) {
        CompletableFuture<String> f = passwordFuture;
        if (f != null && !f.isDone()) f.complete(pass); else error("Сейчас пароль не запрашивался.");
    }

    public boolean isAuthorized() {
        return authorized;
    }

    // ---------- интерактив авторизации ----------

    private CompletableFuture<String> onParameterRequest(InputParameter parameter, ParameterInfo info) {
        switch (parameter) {
            case ASK_CODE -> {
                codeFuture = new CompletableFuture<>();
                info("Введи код из Telegram: /tg code <код>");
                return codeFuture;
            }
            case ASK_PASSWORD -> {
                passwordFuture = new CompletableFuture<>();
                info("Включена 2FA. Введи пароль: /tg password <пароль>");
                return passwordFuture;
            }
            case NOTIFY_LINK -> {
                if (info instanceof ParameterInfoNotifyLink link) showQr(link.getLink());
                return CompletableFuture.completedFuture("");
            }
            default -> {
                return CompletableFuture.completedFuture("");
            }
        }
    }

    private void showQr(String link) {
        try {
            Path png = QrRenderer.render(link);
            MutableComponent open = Component.literal("[Открыть QR-код]")
                    .withStyle(s -> s.withColor(ChatFormatting.GREEN).withUnderlined(true)
                            .withClickEvent(new ClickEvent.OpenFile(png.toAbsolutePath().toString()))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Открыть картинку с QR"))));
            say(tag().append(Component.literal("Отсканируй QR в Telegram на телефоне: ")
                    .withStyle(ChatFormatting.GRAY)).append(open));
            say(tag().append(Component.literal("Файл: " + png.toAbsolutePath()).withStyle(ChatFormatting.DARK_GRAY)));
        } catch (Exception e) {
            error("Не удалось создать QR: " + e);
        }
    }

    private void onAuthState(TdApi.UpdateAuthorizationState u) {
        TdApi.AuthorizationState s = u.authorizationState;
        if (s instanceof TdApi.AuthorizationStateReady) {
            authorized = true;
            say(tag().append(Component.literal("Аккаунт подключён!").withStyle(ChatFormatting.GREEN)));
        } else if (s instanceof TdApi.AuthorizationStateClosed) {
            authorized = false;
        }
    }

    // ---------- входящие сообщения ----------

    private void onNewMessage(TdApi.UpdateNewMessage u) {
        if (!config.notifications || client == null) return;
        TdApi.Message m = u.message;
        if (m.isOutgoing) return;
        // личные чаты имеют положительный id; группы и каналы отрицательные
        if (m.chatId <= 0) return;
        if (!(m.senderId instanceof TdApi.MessageSenderUser sender)) return;

        String body = describe(m.content);

        client.send(new TdApi.GetUser(sender.userId)).whenComplete((user, err) -> {
            if (err != null || user == null) return;
            if (user.type instanceof TdApi.UserTypeBot) return;

            String name = (user.firstName + " " + user.lastName).trim();
            String username = null;
            if (user.usernames != null && user.usernames.activeUsernames.length > 0) {
                username = user.usernames.activeUsernames[0];
                usernameCache.put(username.toLowerCase(), m.chatId);
            }
            lastChatId = m.chatId;

            String suggest = username != null ? "/r @" + username + " " : "/r #" + m.chatId + " ";

            MutableComponent reply = Component.literal("[Ответить]")
                    .withStyle(s -> s.withColor(ChatFormatting.GREEN).withUnderlined(true)
                            .withClickEvent(new ClickEvent.SuggestCommand(suggest))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Подставить " + suggest))));

            say(tag()
                    .append(Component.literal(name + (username != null ? " (@" + username + ")" : ""))
                            .withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(": " + body + " ").withStyle(ChatFormatting.WHITE))
                    .append(reply));
        });
    }

    private String describe(TdApi.MessageContent c) {
        if (c instanceof TdApi.MessageText t) return t.text.text;
        if (c instanceof TdApi.MessagePhoto p) return "[фото] " + p.caption.text;
        if (c instanceof TdApi.MessageVideo v) return "[видео] " + v.caption.text;
        if (c instanceof TdApi.MessageVoiceNote) return "[голосовое]";
        if (c instanceof TdApi.MessageVideoNote) return "[кружок]";
        if (c instanceof TdApi.MessageSticker st) return "[стикер " + st.sticker.emoji + "]";
        if (c instanceof TdApi.MessageDocument d) return "[файл] " + d.caption.text;
        if (c instanceof TdApi.MessageAnimation) return "[гифка]";
        if (c instanceof TdApi.MessageAudio) return "[аудио]";
        return "[сообщение]";
    }

    // ---------- ответ ----------

    /** Формат: "@user текст", "#chatId текст" или просто "текст" (последнему написавшему). */
    public void reply(String raw) {
        if (client == null || !authorized) {
            error("Сначала войди: /tg login phone <номер> или /tg login qr");
            return;
        }
        String text = raw.trim();
        String first = text.split("\\s+", 2)[0];

        if (first.startsWith("@") && first.length() > 1) {
            String uname = first.substring(1);
            String msg = text.length() > first.length() ? text.substring(first.length()).trim() : "";
            if (msg.isEmpty()) { error("Пустое сообщение."); return; }
            resolveUsername(uname).whenComplete((chatId, err) -> {
                if (err != null || chatId == null) error("Пользователь @" + uname + " не найден.");
                else send(chatId, msg);
            });
        } else if (first.startsWith("#") && first.substring(1).matches("\\d+")) {
            long chatId = Long.parseLong(first.substring(1));
            String msg = text.substring(first.length()).trim();
            if (msg.isEmpty()) { error("Пустое сообщение."); return; }
            send(chatId, msg);
        } else {
            if (lastChatId == 0) { error("Некому отвечать: ещё нет входящих."); return; }
            send(lastChatId, text);
        }
    }

    private CompletableFuture<Long> resolveUsername(String uname) {
        Long cached = usernameCache.get(uname.toLowerCase());
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return client.send(new TdApi.SearchPublicChat(uname)).thenApply(chat -> {
            if (chat.id <= 0) throw new IllegalStateException("not a private chat");
            usernameCache.put(uname.toLowerCase(), chat.id);
            return chat.id;
        });
    }

    private void send(long chatId, String text) {
        TdApi.InputMessageText content = new TdApi.InputMessageText(
                new TdApi.FormattedText(text, new TdApi.TextEntity[0]), null, false);
        client.send(new TdApi.SendMessage(chatId, 0, null, null, null, content))
                .whenComplete((msg, err) -> {
                    if (err != null) {
                        error("Не отправлено: " + err.getMessage());
                    } else {
                        lastChatId = chatId;
                        say(tag().append(Component.literal("✔ Отправлено: " + text).withStyle(ChatFormatting.DARK_GREEN)));
                    }
                });
    }

    public void toggleNotifications() {
        config.notifications = !config.notifications;
        config.save();
        info("Уведомления: " + (config.notifications ? "включены" : "выключены"));
    }
}