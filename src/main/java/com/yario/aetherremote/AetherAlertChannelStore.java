package com.yario.aetherremote;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

final class AetherAlertChannelStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(AetherRemoteControlMod.MOD_ID);
    private static final Path LOCAL_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("aether-remote-alert-channel.txt");
    private static final Path SHARED_PATH = resolveSharedPath();

    private AetherAlertChannelStore() {
    }

    static String load() {
        String sharedChannel = read(SHARED_PATH);
        if (!sharedChannel.isBlank()) {
            return sharedChannel;
        }

        return read(LOCAL_PATH);
    }

    static void save(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return;
        }

        write(LOCAL_PATH, channelId);
        write(SHARED_PATH, channelId);
    }

    private static String read(Path path) {
        if (path == null || !Files.exists(path)) {
            return "";
        }

        try {
            return Files.readString(path, StandardCharsets.UTF_8).trim();
        } catch (IOException exception) {
            LOGGER.warn("Failed to read Aether alert channel", exception);
            return "";
        }
    }

    private static void write(Path path, String channelId) {
        if (path == null) {
            return;
        }

        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, channelId, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            LOGGER.warn("Failed to save Aether alert channel", exception);
        }
    }

    private static Path resolveSharedPath() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            return null;
        }

        return Paths.get(appData, "PrismLauncher", "aether-remote-alert-channel.txt");
    }
}
