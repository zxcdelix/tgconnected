package ru.femboy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public class TgConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int apiId = 0;          // 0 = использовать тестовый токен библиотеки
    public String apiHash = "";
    public String phone = "";      // запоминается после входа по номеру
    public boolean notifications = true;

    public static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("telegramconnected");
    }

    public static TgConfig load() {
        try {
            Path f = dir().resolve("config.json");
            if (Files.exists(f)) {
                return GSON.fromJson(Files.readString(f), TgConfig.class);
            }
        } catch (Exception ignored) {}
        TgConfig c = new TgConfig();
        c.save();
        return c;
    }

    public void save() {
        try {
            Files.createDirectories(dir());
            Files.writeString(dir().resolve("config.json"), GSON.toJson(this));
        } catch (Exception ignored) {}
    }
}