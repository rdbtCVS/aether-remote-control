package com.yario.aetherremote;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AetherRemoteConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(AetherRemoteControlMod.MOD_ID);
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("aether-remote-control.json");
    private static final Path SHARED_CONFIG_PATH = resolveSharedConfigPath();

    String discordBotToken = "";
    String discordApplicationId = "";
    String discordGuildId = "";

    static AetherRemoteConfig load() {
        AetherRemoteConfig config = new AetherRemoteConfig();

        Path path = Files.exists(CONFIG_PATH) ? CONFIG_PATH : SHARED_CONFIG_PATH;
        if (path == null || !Files.exists(path)) {
            return config;
        }

        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            config.discordBotToken = readJsonString(json, "discordBotToken");
            config.discordApplicationId = readJsonString(json, "discordApplicationId");
            config.discordGuildId = readJsonString(json, "discordGuildId");
        } catch (IOException exception) {
            LOGGER.error("Failed to load Aether Remote Control config", exception);
        }

        return config;
    }

    void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            String json = toJson();
            Files.writeString(CONFIG_PATH, json, StandardCharsets.UTF_8);
            if (SHARED_CONFIG_PATH != null) {
                Files.createDirectories(SHARED_CONFIG_PATH.getParent());
                Files.writeString(SHARED_CONFIG_PATH, json, StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            LOGGER.error("Failed to save Aether Remote Control config", exception);
        }
    }

    boolean isDiscordConfigured() {
        return !discordBotToken.isBlank()
                && !discordGuildId.isBlank();
    }

    private String toJson() {
        return "{\n"
                + "  \"discordBotToken\": \"" + escape(discordBotToken) + "\",\n"
                + "  \"discordApplicationId\": \"" + escape(discordApplicationId) + "\",\n"
                + "  \"discordGuildId\": \"" + escape(discordGuildId) + "\"\n"
                + "}\n";
    }

    private static String readJsonString(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return "";
        }

        return unescape(matcher.group(1));
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String unescape(String value) {
        StringBuilder builder = new StringBuilder();
        boolean escaping = false;

        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (escaping) {
                switch (character) {
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case '"' -> builder.append('"');
                    case '\\' -> builder.append('\\');
                    default -> builder.append(character);
                }
                escaping = false;
            } else if (character == '\\') {
                escaping = true;
            } else {
                builder.append(character);
            }
        }

        return builder.toString();
    }

    private static Path resolveSharedConfigPath() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            return null;
        }

        return Paths.get(appData, "PrismLauncher", "aether-remote-control.json");
    }
}
