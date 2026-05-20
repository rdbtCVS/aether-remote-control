package com.yario.aetherremote;

import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class AetherRemoteControlMod implements ClientModInitializer {
    public static final String MOD_ID = "aether_remote_control";
    public static final String AUTH_TOKEN = "MY_SECURE_TOKEN_123";
    private static AetherRemoteControlMod instance;

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final String HOST = "127.0.0.1";
    private static final int REGISTRY_HUB_PORT = 8079;
    private static final int FIRST_PORT = 8080;
    private static final int LAST_PORT = 8120;
    private static final String CONTROL_ENDPOINT = "/aether-control";
    private static final String REGISTRY_ENDPOINT = "/aether-registry";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private HttpServer controlServer;
    private ExecutorService controlExecutor;
    private HttpServer registryHubServer;
    private ExecutorService registryHubExecutor;
    private ScheduledExecutorService registryExecutor;
    private ScheduledFuture<?> registryRefreshTask;
    private DiscordBotService discordBotService;
    private AetherClientInstance clientInstance;
    private boolean ownsRegistryHub;

    @Override
    public void onInitializeClient() {
        instance = this;
        startControlServer();
        startRegistryHubIfAvailable();
        registerClientInstance(controlServer == null ? 0 : controlServer.getAddress().getPort());
        AetherFailsafeMonitor.register();
        AetherClientCommands.register();
        AetherKeybinds.register();
        startDiscordBotIfHubOwner();
        AetherConfigScreen.openIfConfigMissing();
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopHttpServers, "Aether Remote Control Shutdown"));
    }

    static void restartDiscordBotFromConfig() {
        if (instance != null) {
            instance.restartDiscordBot();
        }
    }

    static void reportFailsafeAlert(String message) {
        if (instance != null) {
            instance.publishFailsafeAlert(message);
        }
    }

    private void startControlServer() {
        IOException lastException = null;
        for (int port = FIRST_PORT; port <= LAST_PORT; port++) {
            try {
                controlServer = HttpServer.create(new InetSocketAddress(HOST, port), 0);
                break;
            } catch (IOException exception) {
                lastException = exception;
            }
        }

        if (controlServer == null) {
            LOGGER.error("Failed to start Aether Remote Control HTTP server", lastException);
            MinecraftClientBridge.sendClientFeedback("§c[Remote Control] Failed to start HTTP server on ports "
                    + FIRST_PORT + "-" + LAST_PORT);
            return;
        }

        controlServer.createContext(CONTROL_ENDPOINT, new AetherControlHttpHandler(AUTH_TOKEN));
        controlExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Aether Remote Control HTTP Server");
            thread.setDaemon(true);
            return thread;
        });

        controlServer.setExecutor(controlExecutor);
        controlServer.start();
        LOGGER.info("Aether Remote Control HTTP server listening on http://{}:{}{}",
                HOST, controlServer.getAddress().getPort(), CONTROL_ENDPOINT);
    }

    private void startRegistryHubIfAvailable() {
        try {
            registryHubServer = HttpServer.create(new InetSocketAddress(HOST, REGISTRY_HUB_PORT), 0);
            registryHubServer.createContext(REGISTRY_ENDPOINT, new AetherRegistryHttpHandler(AUTH_TOKEN, this::handleHubFailsafeAlert));
            registryHubExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Aether Registry Hub");
                thread.setDaemon(true);
                return thread;
            });
            registryHubServer.setExecutor(registryHubExecutor);
            registryHubServer.start();
            ownsRegistryHub = true;
            LOGGER.info("Aether registry hub listening on http://{}:{}{}", HOST, REGISTRY_HUB_PORT, REGISTRY_ENDPOINT);
        } catch (IOException exception) {
            ownsRegistryHub = false;
            LOGGER.info("Aether registry hub already exists. This client will connect to it.");
        }
    }

    private void registerClientInstance(int port) {
        if (port <= 0) {
            return;
        }

        String accountName = Minecraft.getInstance().getUser().getName();
        String instanceId = AetherClientRegistry.instanceIdFromAccountName(accountName);
        clientInstance = new AetherClientInstance(instanceId, accountName, port, Instant.now().toEpochMilli());
        publishRegistration(clientInstance);

        registryExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Aether Client Registry Refresh");
            thread.setDaemon(true);
            return thread;
        });
        registryRefreshTask = registryExecutor.scheduleAtFixedRate(() -> {
            clientInstance = new AetherClientInstance(instanceId, accountName, port, Instant.now().toEpochMilli());
            publishRegistration(clientInstance);
        }, 10L, 10L, TimeUnit.SECONDS);

        MinecraftClientBridge.sendClientFeedback("§a[Remote Control] Registered client " + instanceId
                + " on port " + port + (ownsRegistryHub ? " as bot host" : " with bot host"));
    }

    private void publishRegistration(AetherClientInstance instance) {
        if (ownsRegistryHub) {
            AetherClientRegistry.register(instance);
            return;
        }

        sendRegistryRequest("register", "id=" + urlEncode(instance.id())
                + "&accountName=" + urlEncode(instance.accountName())
                + "&port=" + instance.port());
    }

    private void publishUnregistration(AetherClientInstance instance) {
        if (ownsRegistryHub) {
            AetherClientRegistry.unregister(instance.id());
            return;
        }

        sendRegistryRequest("unregister", "id=" + urlEncode(instance.id()));
    }

    private void publishFailsafeAlert(String message) {
        if (clientInstance == null) {
            return;
        }

        if (ownsRegistryHub) {
            handleHubFailsafeAlert(clientInstance.id(), message);
            return;
        }

        sendRegistryRequest("failsafe", "id=" + urlEncode(clientInstance.id())
                + "&message=" + urlEncode(message));
    }

    private void handleHubFailsafeAlert(String instanceId, String message) {
        if (discordBotService == null) {
            LOGGER.warn("Failsafe alert received for {}, but Discord bot is not running.", instanceId);
            return;
        }

        AetherClientRegistry.findActive(instanceId)
                .ifPresentOrElse(
                        client -> discordBotService.sendFailsafeAlert(client, message),
                        () -> LOGGER.warn("Failsafe alert received for unknown client {}", instanceId)
                );
    }

    private void sendRegistryRequest(String action, String query) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + HOST + ":" + REGISTRY_HUB_PORT + REGISTRY_ENDPOINT + "/" + action + "?" + query))
                .timeout(Duration.ofSeconds(3))
                .header("X-Remote-Auth", AUTH_TOKEN)
                .GET()
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        LOGGER.warn("Failed to contact Aether registry hub for {}", action, throwable);
                    } else if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        LOGGER.warn("Aether registry hub rejected {} with HTTP {}", action, response.statusCode());
                    }
                });
    }

    private void stopHttpServers() {
        if (discordBotService != null) {
            discordBotService.stop();
            discordBotService = null;
        }

        if (registryRefreshTask != null) {
            registryRefreshTask.cancel(false);
            registryRefreshTask = null;
        }

        if (registryExecutor != null) {
            registryExecutor.shutdownNow();
            registryExecutor = null;
        }

        if (clientInstance != null) {
            publishUnregistration(clientInstance);
            clientInstance = null;
        }

        if (controlServer != null) {
            controlServer.stop(0);
            controlServer = null;
        }

        if (controlExecutor != null) {
            controlExecutor.shutdownNow();
            controlExecutor = null;
        }

        if (registryHubServer != null) {
            registryHubServer.stop(0);
            registryHubServer = null;
        }

        if (registryHubExecutor != null) {
            registryHubExecutor.shutdownNow();
            registryHubExecutor = null;
        }

        LOGGER.info("Aether Remote Control HTTP servers stopped");
    }

    void restartDiscordBot() {
        if (discordBotService != null) {
            discordBotService.stop();
            discordBotService = null;
        }

        startDiscordBotIfHubOwner();
    }

    private void startDiscordBotIfHubOwner() {
        if (!ownsRegistryHub) {
            LOGGER.info("Discord bot runs on the Aether registry hub owner only.");
            return;
        }

        AetherRemoteConfig config = AetherRemoteConfig.load();
        if (!config.isDiscordConfigured()) {
            LOGGER.info("Discord bot is not configured yet. Open the Aether Remote Control config screen in Minecraft.");
            return;
        }

        discordBotService = new DiscordBotService(config);
        discordBotService.start();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
