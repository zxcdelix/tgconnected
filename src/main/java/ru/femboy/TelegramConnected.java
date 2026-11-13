package ru.femboy;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class TelegramConnected implements ClientModInitializer {
    public static TgService tg;

    @Override
    public void onInitializeClient() {
        TgConfig config = TgConfig.load();
        tg = new TgService(config);
        tg.autoStart();

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> tg.flushPending());

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("tg")
                    .then(ClientCommands.literal("login")
                            .then(ClientCommands.literal("phone")
                                    .then(ClientCommands.argument("number", StringArgumentType.word())
                                            .executes(ctx -> {
                                                tg.loginPhone(StringArgumentType.getString(ctx, "number"));
                                                return 1;
                                            })))
                            .then(ClientCommands.literal("qr")
                                    .executes(ctx -> { tg.loginQr(); return 1; })))
                    .then(ClientCommands.literal("code")
                            .then(ClientCommands.argument("code", StringArgumentType.word())
                                    .executes(ctx -> {
                                        tg.submitCode(StringArgumentType.getString(ctx, "code"));
                                        return 1;
                                    })))
                    .then(ClientCommands.literal("password")
                            .then(ClientCommands.argument("pass", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        tg.submitPassword(StringArgumentType.getString(ctx, "pass"));
                                        return 1;
                                    })))
                    .then(ClientCommands.literal("logout")
                            .executes(ctx -> { tg.logout(); return 1; }))
                    .then(ClientCommands.literal("notify")
                            .executes(ctx -> { tg.toggleNotifications(); return 1; }))
                    .then(ClientCommands.literal("status")
                            .executes(ctx -> {
                                tg.info(tg.isAuthorized() ? "Подключено" : "Не подключено");
                                return 1;
                            })));

            dispatcher.register(ClientCommands.literal("r")
                    .then(ClientCommands.argument("text", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                tg.reply(StringArgumentType.getString(ctx, "text"));
                                return 1;
                            })));
        });
    }
}