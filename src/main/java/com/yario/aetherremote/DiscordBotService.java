package com.yario.aetherremote;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DiscordBotService implements WebSocket.Listener {
    private static final Logger LOGGER = LoggerFactory.getLogger(AetherRemoteControlMod.MOD_ID);
    private static final URI DISCORD_GATEWAY = URI.create("wss://gateway.discord.gg/?v=10&encoding=json");
    private static final String DISCORD_API = "https://discord.com/api/v10";

    private final AetherRemoteConfig config;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final StringBuilder messageBuffer = new StringBuilder();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);

    private volatile WebSocket webSocket;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile int lastSequence = -1;
    private volatile boolean stopping;

    DiscordBotService(AetherRemoteConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Aether Discord Bot");
            thread.setDaemon(true);
            return thread;
        });
    }

    void start() {
        scheduler.execute(() -> {
            try {
                registerSlashCommands();
                connectGateway();
            } catch (RuntimeException exception) {
                LOGGER.error("Failed to start Aether Discord bot", exception);
                MinecraftClientBridge.sendClientFeedback("\u00a7c[Remote Control] Discord bot failed to start. Check config.");
            }
        });
    }

    void stop() {
        stopping = true;
        cancelHeartbeat();
        WebSocket socket = webSocket;
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "Stopping");
        }

        scheduler.shutdownNow();
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        this.webSocket = webSocket;
        webSocket.request(1);
        LOGGER.info("Aether Discord bot connected to gateway.");
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        try {
            messageBuffer.append(data);
            if (!last) {
                return CompletableFuture.completedFuture(null);
            }

            String payload = messageBuffer.toString();
            messageBuffer.setLength(0);
            handleGatewayPayload(payload);
            return CompletableFuture.completedFuture(null);
        } finally {
            webSocket.request(1);
        }
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        LOGGER.info("Aether Discord bot disconnected: {} {}", statusCode, reason);
        cancelHeartbeat();
        if (!stopping) {
            scheduleReconnect("gateway closed");
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        LOGGER.error("Aether Discord bot gateway error", error);
        cancelHeartbeat();
        if (!stopping) {
            scheduleReconnect("gateway error");
        }
    }

    private void handleGatewayPayload(String payload) {
        JsonObject root = parseJsonObject(payload);
        if (root == null) {
            LOGGER.warn("Ignoring invalid Discord gateway payload.");
            return;
        }

        Integer sequence = readJsonInt(root, "s");
        if (sequence != null) {
            lastSequence = sequence;
        }

        Integer opCode = readJsonInt(root, "op");
        if (opCode == null) {
            return;
        }

        if (opCode == 1) {
            heartbeat();
            return;
        }

        if (opCode == 7) {
            scheduleReconnect("Discord requested reconnect");
            return;
        }

        if (opCode == 9) {
            lastSequence = -1;
            scheduleReconnect("Discord invalidated the session");
            return;
        }

        if (opCode == 10) {
            JsonObject data = readJsonObject(root, "d");
            Integer heartbeatInterval = data == null ? null : readJsonInt(data, "heartbeat_interval");
            identify();
            if (heartbeatInterval != null) {
                startHeartbeat(heartbeatInterval);
            }
            return;
        }

        if (opCode == 0 && "INTERACTION_CREATE".equals(readJsonString(root, "t"))) {
            JsonObject data = readJsonObject(root, "d");
            if (data != null) {
                handleInteraction(data);
            }
        }
    }

    private void identify() {
        String payload = "{"
                + "\"op\":2,"
                + "\"d\":{"
                + "\"token\":\"" + jsonEscape(config.discordBotToken) + "\","
                + "\"intents\":0,"
                + "\"properties\":{\"os\":\"windows\",\"browser\":\"aether_remote_control\",\"device\":\"aether_remote_control\"}"
                + "}"
                + "}";

        sendGateway(payload);
    }

    private void heartbeat() {
        String sequence = lastSequence < 0 ? "null" : Integer.toString(lastSequence);
        sendGateway("{\"op\":1,\"d\":" + sequence + "}");
    }

    private void handleInteraction(JsonObject interaction) {
        JsonObject commandData = readJsonObject(interaction, "data");
        String commandName = commandData == null ? "" : readJsonString(commandData, "name");
        if (!"aether-start".equals(commandName) && !"aether-stop".equals(commandName)) {
            return;
        }

        String interactionId = readJsonString(interaction, "id");
        String interactionToken = readJsonString(interaction, "token");
        if (interactionId.isBlank() || interactionToken.isBlank()) {
            LOGGER.warn("Discord interaction was missing id or token for command {}", commandName);
            return;
        }

        String localCommand = "aether-start".equals(commandName) ? "start" : "stop";
        String minecraftFeedback = "aether-start".equals(commandName)
                ? "Started Aether farming."
                : "Stopped Aether farming.";

        MinecraftClientBridge.sendClientFeedback("\u00a7a[Remote Control] Discord command received: " + localCommand);
        respondToInteraction(interactionId, interactionToken, minecraftFeedback);
        MinecraftClientBridge.executeSlashCommand(
                "start".equals(localCommand) ? "aether farming" : "aether stop",
                "\u00a7a[Remote Control] Discord triggered " + localCommand + " command"
        );
    }

    private void registerSlashCommands() {
        String body = "["
                + "{\"name\":\"aether-start\",\"description\":\"Start Aether farming\",\"type\":1},"
                + "{\"name\":\"aether-stop\",\"description\":\"Stop Aether farming\",\"type\":1}"
                + "]";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DISCORD_API + "/applications/" + config.discordApplicationId
                        + "/guilds/" + config.discordGuildId + "/commands"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bot " + config.discordBotToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.error("Discord command registration failed: HTTP {} {}", response.statusCode(), response.body());
                MinecraftClientBridge.sendClientFeedback("\u00a7c[Remote Control] Discord command registration failed: HTTP "
                        + response.statusCode() + " " + summarizeDiscordError(response.body()));
            } else {
                LOGGER.info("Aether Discord slash commands registered.");
                MinecraftClientBridge.sendClientFeedback("\u00a7a[Remote Control] Discord slash commands registered.");
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to register Discord slash commands", exception);
        }
    }

    private void respondToInteraction(String interactionId, String interactionToken, String message) {
        String body = "{\"type\":4,\"data\":{\"content\":\"" + jsonEscape(message) + "\",\"flags\":64}}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DISCORD_API + "/interactions/" + interactionId + "/" + interactionToken + "/callback"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        LOGGER.error("Failed to respond to Discord interaction", throwable);
                        MinecraftClientBridge.sendClientFeedback("\u00a7c[Remote Control] Discord interaction response failed.");
                        return;
                    }

                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        LOGGER.error("Discord interaction response failed: HTTP {} {}", response.statusCode(), response.body());
                        MinecraftClientBridge.sendClientFeedback("\u00a7c[Remote Control] Discord response failed: HTTP "
                                + response.statusCode() + " " + summarizeDiscordError(response.body()));
                    }
                });
    }

    private void connectGateway() {
        reconnectScheduled.set(false);
        webSocket = httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .buildAsync(DISCORD_GATEWAY, this)
                .join();
        LOGGER.info("Aether Discord bot is connecting.");
    }

    private void scheduleReconnect(String reason) {
        if (stopping || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }

        LOGGER.info("Scheduling Aether Discord bot reconnect: {}", reason);
        MinecraftClientBridge.sendClientFeedback("\u00a7e[Remote Control] Discord reconnecting: " + reason);
        scheduler.schedule(() -> {
            try {
                cancelHeartbeat();
                WebSocket socket = webSocket;
                if (socket != null) {
                    socket.abort();
                }
                connectGateway();
            } catch (RuntimeException exception) {
                LOGGER.error("Aether Discord bot reconnect failed", exception);
                reconnectScheduled.set(false);
                scheduleReconnect("reconnect failed");
            }
        }, 3, TimeUnit.SECONDS);
    }

    private void startHeartbeat(int heartbeatInterval) {
        cancelHeartbeat();
        heartbeatTask = scheduler.scheduleAtFixedRate(this::heartbeat, heartbeatInterval, heartbeatInterval, TimeUnit.MILLISECONDS);
    }

    private void cancelHeartbeat() {
        ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(false);
            heartbeatTask = null;
        }
    }

    private void sendGateway(String payload) {
        WebSocket socket = webSocket;
        if (socket != null) {
            socket.sendText(payload, true);
        }
    }

    private static Integer readJsonInt(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(null|-?\\d+)").matcher(json);
        if (!matcher.find() || "null".equals(matcher.group(1))) {
            return null;
        }

        return Integer.parseInt(matcher.group(1));
    }

    private static JsonObject parseJsonObject(String json) {
        try {
            JsonElement element = JsonParser.parseString(json);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (JsonSyntaxException exception) {
            LOGGER.warn("Failed to parse Discord gateway JSON", exception);
            return null;
        }
    }

    private static JsonObject readJsonObject(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static Integer readJsonInt(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }

        try {
            return element.getAsInt();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String readJsonString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return "";
        }

        try {
            return element.getAsString();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static String readJsonString(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").matcher(json);
        if (!matcher.find()) {
            return "";
        }

        return matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String jsonEscape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String summarizeDiscordError(String responseBody) {
        String message = readJsonString(responseBody, "message");
        if (!message.isBlank()) {
            return message;
        }

        if (responseBody == null || responseBody.isBlank()) {
            return "No error body";
        }

        return responseBody.length() > 120 ? responseBody.substring(0, 120) : responseBody;
    }
}

