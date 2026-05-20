package com.yario.aetherremote;

import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AetherRemoteControlMod implements ClientModInitializer {
    public static final String MOD_ID = "aether_remote_control";
    public static final String AUTH_TOKEN = "MY_SECURE_TOKEN_123";
    private static AetherRemoteControlMod instance;

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 8080;
    private static final String ENDPOINT = "/aether-control";

    private HttpServer server;
    private ExecutorService executor;
    private DiscordBotService discordBotService;

    @Override
    public void onInitializeClient() {
        instance = this;
        startHttpServer();
        AetherKeybinds.register();
        startDiscordBotIfConfigured();
        AetherConfigScreen.openIfConfigMissing();
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopHttpServer, "Aether Remote Control Shutdown"));
    }

    static void restartDiscordBotFromConfig() {
        if (instance != null) {
            instance.restartDiscordBot();
        }
    }

    private void startHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(HOST, PORT), 0);
            server.createContext(ENDPOINT, new AetherControlHttpHandler(AUTH_TOKEN));

            executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Aether Remote Control HTTP Server");
                thread.setDaemon(true);
                return thread;
            });

            server.setExecutor(executor);
            server.start();

            LOGGER.info("Aether Remote Control HTTP server listening on http://{}:{}{}", HOST, PORT, ENDPOINT);
        } catch (IOException exception) {
            LOGGER.error("Failed to start Aether Remote Control HTTP server", exception);
            MinecraftClientBridge.sendClientFeedback("§c[Remote Control] Failed to start HTTP server on port " + PORT);
        }
    }

    private void stopHttpServer() {
        if (discordBotService != null) {
            discordBotService.stop();
            discordBotService = null;
        }

        if (server != null) {
            server.stop(0);
            server = null;
        }

        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }

        LOGGER.info("Aether Remote Control HTTP server stopped");
    }

    void restartDiscordBot() {
        if (discordBotService != null) {
            discordBotService.stop();
        }

        startDiscordBotIfConfigured();
    }

    private void startDiscordBotIfConfigured() {
        AetherRemoteConfig config = AetherRemoteConfig.load();
        if (!config.isDiscordConfigured()) {
            LOGGER.info("Discord bot is not configured yet. Open the Aether Remote Control config screen in Minecraft.");
            return;
        }

        discordBotService = new DiscordBotService(config);
        discordBotService.start();
    }
}
