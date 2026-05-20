package com.yario.aetherremote;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AetherRemoteConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(AetherRemoteControlMod.MOD_ID);
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("aether-remote-control.json");

    String discordBotToken = "";
    String discordApplicationId = "";
    String discordGuildId = "";

    static AetherRemoteConfig load() {
        AetherRemoteConfig config = new AetherRemoteConfig();

        if (!Files.exists(CONFIG_PATH)) {
            return config;
        }

        try {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
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
            Files.writeString(CONFIG_PATH, toJson(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            LOGGER.error("Failed to save Aether Remote Control config", exception);
        }
    }

    boolean isDiscordConfigured() {
        return !discordBotToken.isBlank()
                && !discordApplicationId.isBlank()
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
}
