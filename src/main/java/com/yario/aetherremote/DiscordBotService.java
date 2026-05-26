package com.yario.aetherremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class DiscordBotService implements WebSocket.Listener {
    private static final Logger LOGGER = LoggerFactory.getLogger(AetherRemoteControlMod.MOD_ID);
    private static final URI DISCORD_GATEWAY = URI.create("wss://gateway.discord.gg/?v=10&encoding=json");
    private static final String DISCORD_API = "https://discord.com/api/v10";
    private static final String PANEL_COMMAND = "!control";
    private static final String LEGACY_PANEL_COMMAND = "!aether";
    private static final String STATUS_COMMAND = "!status";
    private static final int GATEWAY_INTENTS = 1 | 512 | 32768;
    private static final int BUTTON_STYLE_GRAY = 2;
    private static final String BRAND_ATTACHMENT = "aether.png";
    private static final String BRAND_RESOURCE = "/assets/aether_remote_control/aether.png";
    private static final DateTimeFormatter FOOTER_DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy h:mm a");
    private static final String[] WARP_COMMANDS = {
            "/warp hub",
            "/warp garden",
            "/warp desk",
            "/warp island",
            "/warp elizabeth",
            "/warpforge",
            "/play skyblock",
            "/lobby"
    };

    private final AetherRemoteConfig config;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final StringBuilder messageBuffer = new StringBuilder();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final Map<String, String> selectedClientByUser = new ConcurrentHashMap<>();
    private final Map<String, String> panelMessageByChannel = new ConcurrentHashMap<>();
    private final byte[] brandImageBytes = loadBrandImageBytes();

    private volatile WebSocket webSocket;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile int lastSequence = -1;
    private volatile boolean stopping;
    private volatile String recentControlChannelId = AetherAlertChannelStore.load();
    private volatile boolean slashCommandsRegistered;

    private record UploadFile(String fieldName, String filename, String contentType, byte[] bytes) {
    }

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
                connectGateway();
            } catch (RuntimeException exception) {
                LOGGER.error("Failed to start Aether Discord bot", exception);
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

    void sendFailsafeAlert(AetherClientInstance client, String triggerMessage) {
        String channelId = recentControlChannelId;
        if (channelId == null || channelId.isBlank()) {
            LOGGER.warn("Failsafe alert for {} could not be sent because no Discord control channel is known.", client.id());
            MinecraftClientBridge.sendClientFeedback("§c[Remote Control] Failsafe detected, but no Discord alert channel is saved. Use /aether panel once.");
            return;
        }

        scheduler.execute(() -> sendFailsafeAlertMessage(channelId, client, triggerMessage));
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

        if (opCode != 0) {
            return;
        }

        String eventType = readJsonString(root, "t");
        JsonObject data = readJsonObject(root, "d");
        if (data == null) {
            return;
        }

        if ("READY".equals(eventType)) {
            registerSlashCommands();
        } else if ("MESSAGE_CREATE".equals(eventType)) {
            handleMessageCreate(data);
        } else if ("INTERACTION_CREATE".equals(eventType)) {
            handleInteraction(data);
        }
    }

    private void identify() {
        JsonObject payload = new JsonObject();
        payload.addProperty("op", 2);

        JsonObject data = new JsonObject();
        data.addProperty("token", config.discordBotToken);
        data.addProperty("intents", GATEWAY_INTENTS);

        JsonObject properties = new JsonObject();
        properties.addProperty("os", "windows");
        properties.addProperty("browser", "aether_remote_control");
        properties.addProperty("device", "aether_remote_control");
        data.add("properties", properties);

        payload.add("d", data);
        sendGateway(payload.toString());
    }

    private void heartbeat() {
        String sequence = lastSequence < 0 ? "null" : Integer.toString(lastSequence);
        sendGateway("{\"op\":1,\"d\":" + sequence + "}");
    }

    private void handleMessageCreate(JsonObject message) {
        if (!config.discordGuildId.isBlank() && !config.discordGuildId.equals(readJsonString(message, "guild_id"))) {
            return;
        }

        JsonObject author = readJsonObject(message, "author");
        if (author != null && readJsonBoolean(author, "bot")) {
            return;
        }

        String content = readJsonString(message, "content").trim();
        String channelId = readJsonString(message, "channel_id");
        rememberControlChannel(channelId);
        if (STATUS_COMMAND.equalsIgnoreCase(content)) {
            if (!channelId.isBlank()) {
                sendTextStatus(channelId);
            }
            return;
        }

        if (!PANEL_COMMAND.equalsIgnoreCase(content) && !LEGACY_PANEL_COMMAND.equalsIgnoreCase(content)) {
            return;
        }

        if (!channelId.isBlank()) {
            sendSelectionPanel(channelId);
        }
    }

    private void handleInteraction(JsonObject interaction) {
        if (!config.discordGuildId.isBlank() && !config.discordGuildId.equals(readJsonString(interaction, "guild_id"))) {
            return;
        }

        Integer interactionType = readJsonInt(interaction, "type");
        if (interactionType == null || (interactionType != 2 && interactionType != 3 && interactionType != 4 && interactionType != 5)) {
            return;
        }

        JsonObject data = readJsonObject(interaction, "data");
        String customId = data == null ? "" : readJsonString(data, "custom_id");
        String interactionId = readJsonString(interaction, "id");
        String interactionToken = readJsonString(interaction, "token");
        String userId = readInteractionUserId(interaction);
        String channelId = readJsonString(interaction, "channel_id");
        rememberControlChannel(channelId);
        JsonObject interactionMessage = readJsonObject(interaction, "message");
        String messageId = interactionMessage == null ? "" : readJsonString(interactionMessage, "id");
        if (interactionId.isBlank() || interactionToken.isBlank()) {
            LOGGER.warn("Discord component interaction was missing required ids.");
            return;
        }

        if (interactionType == 2) {
            handleSlashCommand(interactionId, interactionToken, channelId, data);
            return;
        }

        if (interactionType == 4) {
            handleSlashAutocomplete(interactionId, interactionToken, data);
            return;
        }

        if (userId.isBlank()) {
            LOGGER.warn("Discord component interaction was missing a user id.");
            return;
        }

        if (interactionType == 3 && !isActivePanel(channelId, messageId)) {
            updateInteractionMessage(interactionId, interactionToken, buildExpiredPanelEmbed(), new JsonArray());
            return;
        }

        if (!channelId.isBlank() && !messageId.isBlank()) {
            panelMessageByChannel.putIfAbsent(channelId, messageId);
        }

        if (interactionType == 5 && customId.startsWith("aether:chat:")) {
            handleChatModal(interactionId, interactionToken, channelId, customId.substring("aether:chat:".length()), data);
            return;
        }

        if ("aether:select-player".equals(customId)) {
            String clientId = readFirstSelectedValue(data);
            Optional<AetherClientInstance> client = AetherClientRegistry.findActive(clientId);
            if (client.isEmpty()) {
                updateInteractionMessage(interactionId, interactionToken, buildSelectionEmbed("That player is no longer online."), buildPlayerSelect());
                return;
            }

            selectedClientByUser.put(userId, clientId);
            updateInteractionMessage(interactionId, interactionToken, buildRemoteEmbed(client.get(), "Connected."), buildRemoteButtons(client.get()));
            return;
        }

        if (customId.startsWith("aether:warp:")) {
            String clientId = customId.substring("aether:warp:".length());
            Optional<AetherClientInstance> client = AetherClientRegistry.findActive(clientId);
            String warpCommand = readFirstSelectedValue(data);
            if (client.isEmpty() || warpCommand.isBlank()) {
                updateInteractionMessage(interactionId, interactionToken, buildSelectionEmbed("That warp is no longer available."), buildPlayerSelect());
                return;
            }

            executeChatInput(client.get(), warpCommand);
            deferInteractionUpdate(interactionId, interactionToken);
            scheduler.schedule(() -> editControlMessageWithScreenshot(channelId, messageId, client.get(),
                    "Warped with `" + warpCommand + "`.", buildRemoteButtons(client.get())), 7L, TimeUnit.SECONDS);
            return;
        }

        if (!customId.startsWith("aether:command:")) {
            return;
        }

        String[] parts = customId.split(":", 4);
        if (parts.length != 4) {
            updateInteractionMessage(interactionId, interactionToken, buildSelectionEmbed("That control is invalid."), buildPlayerSelect());
            return;
        }

        String clientId = parts[2];
        String command = parts[3];
        Optional<AetherClientInstance> client = AetherClientRegistry.findActive(clientId);
        if (client.isEmpty()) {
            updateInteractionMessage(interactionId, interactionToken, buildSelectionEmbed("That player is no longer online."), buildPlayerSelect());
            return;
        }

        if ("end".equals(command)) {
            selectedClientByUser.remove(userId);
            updateInteractionMessage(interactionId, interactionToken, buildSelectionEmbed("Choose another player."), buildPlayerSelect());
            return;
        }

        if ("warps".equals(command)) {
            updateInteractionMessage(interactionId, interactionToken, buildRemoteEmbed(client.get(), "Choose a warp."), buildWarpComponents(client.get()));
            return;
        }

        if ("back".equals(command)) {
            updateInteractionMessage(interactionId, interactionToken, buildRemoteEmbed(client.get(), "Connected."), buildRemoteButtons(client.get()));
            return;
        }

        if ("chat".equals(command)) {
            openChatModal(interactionId, interactionToken, client.get(), messageId);
            return;
        }

        if ("connect".equals(command) || "disconnect".equals(command) || "panic".equals(command)) {
            String result = executeClientAction(client.get(), command);
            if ("panic".equals(command)) {
                updateInteractionMessage(interactionId, interactionToken, buildRemoteEmbed(client.get(), result), buildRemoteButtons(client.get()));
            } else {
                deferInteractionUpdate(interactionId, interactionToken);
                long delay = "connect".equals(command) ? 7L : 2L;
                scheduler.schedule(() -> editControlMessageWithScreenshot(channelId, messageId, client.get(), result, buildRemoteButtons(client.get())),
                        delay, TimeUnit.SECONDS);
            }
            return;
        }

        if ("status".equals(command)) {
            deferInteractionUpdate(interactionId, interactionToken);
            scheduler.execute(() -> editControlMessageWithScreenshot(channelId, messageId, client.get(),
                    executeCommand(client.get(), "status"), buildRemoteButtons(client.get())));
            return;
        }

        String result = executeCommand(client.get(), command);
        deferInteractionUpdate(interactionId, interactionToken);
        scheduler.schedule(() -> editControlMessageWithScreenshot(channelId, messageId, client.get(), result, buildRemoteButtons(client.get())),
                1L, TimeUnit.SECONDS);
    }

    private String executeCommand(AetherClientInstance client, String command) {
        if (!"start".equals(command) && !"stop".equals(command) && !"status".equals(command)) {
            return "Unsupported command.";
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(client.controlUrl(urlEncode(command))))
                .timeout(Duration.ofSeconds(5))
                .header("X-Remote-Auth", AetherRemoteControlMod.AUTH_TOKEN)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "Command failed with HTTP " + response.statusCode() + ".";
            }
            return switch (command) {
                case "start" -> "`/aether farming` sent.";
                case "stop" -> "`/aether stop` sent.";
                case "status" -> "`/aether status` sent.";
                default -> "`" + command + "` sent.";
            };
        } catch (Exception exception) {
            LOGGER.error("Failed to send command to Minecraft client {}", client.id(), exception);
            return "Could not reach that Minecraft client.";
        }
    }

    private String executeChatInput(AetherClientInstance client, String message) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(client.controlUrl("chat", "message=" + urlEncode(message))))
                .timeout(Duration.ofSeconds(5))
                .header("X-Remote-Auth", AetherRemoteControlMod.AUTH_TOKEN)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "Chat input failed with HTTP " + response.statusCode() + ".";
            }
            return "Sent `" + message + "`.";
        } catch (Exception exception) {
            LOGGER.error("Failed to send chat input to Minecraft client {}", client.id(), exception);
            return "Could not reach that Minecraft client.";
        }
    }

    private String executeClientAction(AetherClientInstance client, String action) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(client.controlUrl(urlEncode(action))))
                .timeout(Duration.ofSeconds(5))
                .header("X-Remote-Auth", AetherRemoteControlMod.AUTH_TOKEN)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "`" + action + "` failed with HTTP " + response.statusCode() + ".";
            }

            return switch (action) {
                case "connect" -> "Connecting to `hypixel.net`.";
                case "disconnect" -> "Disconnect requested.";
                case "panic" -> "Panic crash requested.";
                default -> "`" + action + "` sent.";
            };
        } catch (Exception exception) {
            LOGGER.error("Failed to send {} to Minecraft client {}", action, client.id(), exception);
            return "Could not reach that Minecraft client.";
        }
    }

    private String executeSlashTask(AetherClientInstance client, String task, String message) {
        if ("start".equals(task) || "stop".equals(task) || "status".equals(task)) {
            return executeCommand(client, task);
        }

        if ("connect".equals(task) || "disconnect".equals(task) || "panic".equals(task)) {
            return executeClientAction(client, task);
        }

        if ("chat".equals(task)) {
            if (message == null || message.isBlank()) {
                return "Chat needs a message.";
            }
            return executeChatInput(client, message.trim());
        }

        if (isWarpTask(task)) {
            return executeChatInput(client, task);
        }

        return "Unsupported slash task.";
    }

    private boolean isWarpTask(String task) {
        for (String warpCommand : WARP_COMMANDS) {
            if (warpCommand.equals(task)) {
                return true;
            }
        }
        return false;
    }

    private void handleSlashCommand(String interactionId, String interactionToken, String channelId, JsonObject data) {
        if (data == null || !"aether".equals(readJsonString(data, "name"))) {
            return;
        }

        rememberControlChannel(channelId);
        String userOption = readSlashOptionValue(data, "user");
        String task = readSlashOptionValue(data, "task");
        String message = readSlashOptionValue(data, "message");

        if ("panel".equals(task)) {
            if (!channelId.isBlank()) {
                sendSelectionPanel(channelId);
            }
            respondInteractionMessage(interactionId, interactionToken, buildSelectionEmbed("Control panel sent."), new JsonArray());
            return;
        }

        if (userOption.isBlank()) {
            respondInteractionMessage(interactionId, interactionToken, buildSelectionEmbed("You must specify a player for this task."), new JsonArray());
            return;
        }

        Optional<AetherClientInstance> client = findClientForSlashValue(userOption);
        if (client.isEmpty()) {
            respondInteractionMessage(interactionId, interactionToken, buildSelectionEmbed("That player is not online."), new JsonArray());
            return;
        }

        if (task.isBlank()) {
            respondInteractionMessage(interactionId, interactionToken, buildRemoteEmbed(client.get(), "Pick a task."), buildRemoteButtons(client.get()));
            return;
        }

        deferInteractionChannelMessage(interactionId, interactionToken);
        scheduler.execute(() -> {
            String result = executeSlashTask(client.get(), task, message);
            long screenshotDelay = isWarpTask(task) ? 7000L : 1000L;
            if ("connect".equals(task)) {
                screenshotDelay = 7000L;
            } else if ("disconnect".equals(task)) {
                screenshotDelay = 2000L;
            } else if ("panic".equals(task)) {
                screenshotDelay = 0L;
            }

            if (screenshotDelay > 0) {
                try {
                    Thread.sleep(screenshotDelay);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            editOriginalSlashResponseWithScreenshot(interactionToken, client.get(), result);
        });
    }

    private void handleSlashAutocomplete(String interactionId, String interactionToken, JsonObject data) {
        String focusedName = "";
        String currentValue = "";
        JsonArray options = data == null ? null : readJsonArray(data, "options");
        if (options != null) {
            for (JsonElement optionElement : options) {
                if (!optionElement.isJsonObject()) {
                    continue;
                }
                JsonObject option = optionElement.getAsJsonObject();
                if (readJsonBoolean(option, "focused")) {
                    focusedName = readJsonString(option, "name");
                    currentValue = readJsonString(option, "value").toLowerCase();
                    break;
                }
            }
        }

        JsonArray choices = new JsonArray();
        if ("user".equals(focusedName)) {
            for (AetherClientInstance client : AetherClientRegistry.readActive()) {
                String label = client.accountName();
                if (currentValue.isBlank() || label.toLowerCase().contains(currentValue) || client.id().toLowerCase().contains(currentValue)) {
                    JsonObject choice = new JsonObject();
                    choice.addProperty("name", label);
                    choice.addProperty("value", client.id());
                    choices.add(choice);
                    if (choices.size() >= 25) {
                        break;
                    }
                }
            }
        }

        JsonObject body = new JsonObject();
        body.addProperty("type", 8);
        JsonObject responseData = new JsonObject();
        responseData.add("choices", choices);
        body.add("data", responseData);
        postInteractionCallback(interactionId, interactionToken, body);
    }

    private Optional<AetherClientInstance> findClientForSlashValue(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        Optional<AetherClientInstance> exact = AetherClientRegistry.findActive(value);
        if (exact.isPresent()) {
            return exact;
        }

        String normalizedValue = value.trim().toLowerCase();
        return AetherClientRegistry.readActive().stream()
                .filter(client -> client.accountName().toLowerCase().equals(normalizedValue))
                .findFirst();
    }

    private String readSlashOptionValue(JsonObject data, String name) {
        JsonArray options = data == null ? null : readJsonArray(data, "options");
        if (options == null) {
            return "";
        }

        for (JsonElement optionElement : options) {
            if (!optionElement.isJsonObject()) {
                continue;
            }

            JsonObject option = optionElement.getAsJsonObject();
            if (name.equals(readJsonString(option, "name"))) {
                return readJsonString(option, "value");
            }
        }

        return "";
    }

    private void handleChatModal(String interactionId, String interactionToken, String channelId, String modalPayload, JsonObject data) {
        String clientId = modalPayload;
        String sourceMessageId = "";
        int separatorIndex = modalPayload.lastIndexOf(':');
        if (separatorIndex > 0 && separatorIndex < modalPayload.length() - 1) {
            clientId = modalPayload.substring(0, separatorIndex);
            sourceMessageId = modalPayload.substring(separatorIndex + 1);
        }

        if (!sourceMessageId.isBlank() && !isActivePanel(channelId, sourceMessageId)) {
            updateInteractionMessage(interactionId, interactionToken, buildExpiredPanelEmbed(), new JsonArray());
            return;
        }

        Optional<AetherClientInstance> client = AetherClientRegistry.findActive(clientId);
        if (client.isEmpty()) {
            updateInteractionMessage(interactionId, interactionToken, buildSelectionEmbed("That player is no longer online."), buildPlayerSelect());
            return;
        }

        String message = readModalTextValue(data, "aether:chat-message").trim();
        if (message.isBlank()) {
            updateInteractionMessage(interactionId, interactionToken, buildRemoteEmbed(client.get(), "Chat message cannot be blank."), buildRemoteButtons(client.get()));
            return;
        }

        String result = executeChatInput(client.get(), message);
        updateInteractionMessage(interactionId, interactionToken, buildRemoteEmbed(client.get(), result), buildRemoteButtons(client.get()));
        String panelMessageId = panelMessageByChannel.get(channelId);
        if (panelMessageId != null && !panelMessageId.isBlank()) {
            scheduler.schedule(() -> editControlMessageWithScreenshot(channelId, panelMessageId, client.get(), result, buildRemoteButtons(client.get())),
                    1L, TimeUnit.SECONDS);
        }
    }

    private void openChatModal(String interactionId, String interactionToken, AetherClientInstance client, String messageId) {
        JsonObject body = new JsonObject();
        body.addProperty("type", 9);

        JsonObject data = new JsonObject();
        data.addProperty("custom_id", "aether:chat:" + client.id() + ":" + messageId);
        data.addProperty("title", "Chat as " + client.accountName());

        JsonArray rows = new JsonArray();
        JsonObject row = new JsonObject();
        row.addProperty("type", 1);
        JsonArray components = new JsonArray();
        JsonObject input = new JsonObject();
        input.addProperty("type", 4);
        input.addProperty("custom_id", "aether:chat-message");
        input.addProperty("label", "Minecraft chat or command");
        input.addProperty("style", 2);
        input.addProperty("placeholder", "Type a message, or /warp hub");
        input.addProperty("min_length", 1);
        input.addProperty("max_length", 256);
        input.addProperty("required", true);
        components.add(input);
        row.add("components", components);
        rows.add(row);
        data.add("components", rows);
        body.add("data", data);

        postInteractionCallback(interactionId, interactionToken, body);
    }

    private void sendSelectionPanel(String channelId) {
        String previousPanelMessageId = panelMessageByChannel.get(channelId);

        JsonObject body = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(buildSelectionEmbed(""));
        body.add("embeds", embeds);
        body.add("components", buildPlayerSelect());

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(DISCORD_API + "/channels/" + channelId + "/messages"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bot " + config.discordBotToken);

        HttpRequest request;
        if (hasBrandImage()) {
            String boundary = "AetherBoundary" + Instant.now().toEpochMilli();
            body.add("attachments", attachmentsJson(List.of(brandUploadFile(0))));
            requestBuilder.header("Content-Type", "multipart/form-data; boundary=" + boundary);
            HttpRequest.BodyPublisher bodyPublisher = ofMultipartData(boundary, body.toString(), List.of(brandUploadFile(0)));
            request = requestBuilder.POST(bodyPublisher).build();
        } else {
            requestBuilder.header("Content-Type", "application/json");
            request = requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        }

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        LOGGER.error("Failed to send Aether Discord panel", throwable);
                        return;
                    }

                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        LOGGER.error("Discord panel send failed: HTTP {} {}", response.statusCode(), response.body());
                        return;
                    }

                    JsonObject message = parseJsonObject(response.body());
                    String messageId = message == null ? "" : readJsonString(message, "id");
                    if (!messageId.isBlank()) {
                        panelMessageByChannel.put(channelId, messageId);
                        if (previousPanelMessageId != null
                                && !previousPanelMessageId.isBlank()
                                && !previousPanelMessageId.equals(messageId)) {
                            expirePanelMessage(channelId, previousPanelMessageId);
                        }
                    }
                });
    }

    private void registerSlashCommands() {
        if (slashCommandsRegistered || config.discordApplicationId.isBlank() || config.discordGuildId.isBlank()) {
            return;
        }

        slashCommandsRegistered = true;
        JsonArray commands = new JsonArray();
        commands.add(buildAetherSlashCommand());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DISCORD_API + "/applications/" + config.discordApplicationId
                        + "/guilds/" + config.discordGuildId + "/commands"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bot " + config.discordBotToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(commands.toString(), StandardCharsets.UTF_8))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        slashCommandsRegistered = false;
                        LOGGER.error("Failed to register Aether slash command", throwable);
                        return;
                    }

                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        slashCommandsRegistered = false;
                        LOGGER.error("Aether slash command registration failed: HTTP {} {}", response.statusCode(), response.body());
                    } else {
                        LOGGER.info("Aether slash command registered.");
                    }
                });
    }

    private JsonObject buildAetherSlashCommand() {
        JsonObject command = new JsonObject();
        command.addProperty("name", "aether");
        command.addProperty("description", "Remote control an Aether Minecraft client");

        JsonArray options = new JsonArray();
        JsonObject task = new JsonObject();
        task.addProperty("type", 3);
        task.addProperty("name", "task");
        task.addProperty("description", "Task to run");
        task.addProperty("required", true);
        JsonArray choices = new JsonArray();
        addSlashChoice(choices, "panel", "panel");
        addSlashChoice(choices, "start", "start");
        addSlashChoice(choices, "stop", "stop");
        addSlashChoice(choices, "status", "status");
        addSlashChoice(choices, "connect", "connect");
        addSlashChoice(choices, "disconnect", "disconnect");
        addSlashChoice(choices, "panic", "panic");
        addSlashChoice(choices, "chat", "chat");
        for (String warpCommand : WARP_COMMANDS) {
            addSlashChoice(choices, warpCommand, warpCommand);
        }
        task.add("choices", choices);
        options.add(task);

        JsonObject user = new JsonObject();
        user.addProperty("type", 3);
        user.addProperty("name", "user");
        user.addProperty("description", "Minecraft account to control");
        user.addProperty("required", false);
        user.addProperty("autocomplete", true);
        options.add(user);

        JsonObject message = new JsonObject();
        message.addProperty("type", 3);
        message.addProperty("name", "message");
        message.addProperty("description", "Message to send when task is chat");
        message.addProperty("required", false);
        options.add(message);

        command.add("options", options);
        return command;
    }

    private void addSlashChoice(JsonArray choices, String name, String value) {
        JsonObject choice = new JsonObject();
        choice.addProperty("name", name);
        choice.addProperty("value", value);
        choices.add(choice);
    }

    private boolean isActivePanel(String channelId, String messageId) {
        if (channelId.isBlank() || messageId.isBlank()) {
            return true;
        }

        String activeMessageId = panelMessageByChannel.get(channelId);
        return activeMessageId == null || activeMessageId.isBlank() || activeMessageId.equals(messageId);
    }

    private JsonObject buildExpiredPanelEmbed() {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "Aether Remote Control");
        embed.addProperty("description", "This control panel expired. Use `/aether panel` for a fresh panel.");
        embed.addProperty("color", 0x747F8D);
        JsonObject footer = new JsonObject();
        addBrandFooter(footer);
        embed.add("footer", footer);
        return embed;
    }

    private void expirePanelMessage(String channelId, String messageId) {
        JsonObject payload = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(buildExpiredPanelEmbed());
        payload.add("embeds", embeds);
        payload.add("components", new JsonArray());
        payload.add("attachments", new JsonArray());

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(DISCORD_API + "/channels/" + channelId + "/messages/" + messageId))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bot " + config.discordBotToken);

        HttpRequest request;
        if (hasBrandImage()) {
            String boundary = "AetherBoundary" + Instant.now().toEpochMilli();
            payload.add("attachments", attachmentsJson(List.of(brandUploadFile(0))));
            request = requestBuilder
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .method("PATCH", ofMultipartData(boundary, payload.toString(), List.of(brandUploadFile(0))))
                    .build();
        } else {
            request = requestBuilder
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                    .build();
        }

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        LOGGER.warn("Failed to expire previous Aether control panel", throwable);
                        return;
                    }

                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        LOGGER.warn("Expiring previous Aether control panel failed: HTTP {} {}", response.statusCode(), response.body());
                    }
                });
    }

    private void sendTextStatus(String channelId) {
        List<AetherClientInstance> clients = AetherClientRegistry.readActive();
        if (clients.isEmpty()) {
            sendPlainChannelMessage(channelId, "No Minecraft clients are registered.");
            return;
        }

        if (clients.size() > 1) {
            sendSelectionPanel(channelId);
            sendPlainChannelMessage(channelId, "Pick a player in the control panel, then press **Status**.");
            return;
        }

        scheduler.execute(() -> sendStatusScreenshot(channelId, clients.get(0)));
    }

    private JsonObject buildSelectionEmbed(String statusLine) {
        List<AetherClientInstance> clients = AetherClientRegistry.readActive();

        JsonObject embed = new JsonObject();
        embed.addProperty("title", "Aether Remote Control");
        embed.addProperty("description", statusLine.isBlank()
                ? "Select the player you would like to remote control"
                : statusLine + "\n\nSelect the player you would like to remote control");
        embed.addProperty("color", clients.isEmpty() ? 0xED4245 : 0x57F287);

        JsonObject footer = new JsonObject();
        addBrandFooter(footer);
        embed.add("footer", footer);

        return embed;
    }

    private JsonArray buildPlayerSelect() {
        List<AetherClientInstance> clients = AetherClientRegistry.readActive();
        JsonArray rows = new JsonArray();

        if (clients.isEmpty()) {
            JsonObject row = new JsonObject();
            row.addProperty("type", 1);
            JsonArray buttons = new JsonArray();
            buttons.add(buildButton("No players online", "aether:none", 2, true));
            row.add("components", buttons);
            rows.add(row);
            return rows;
        }

        JsonObject row = new JsonObject();
        row.addProperty("type", 1);
        JsonArray components = new JsonArray();
        JsonObject select = new JsonObject();
        select.addProperty("type", 3);
        select.addProperty("custom_id", "aether:select-player");
        select.addProperty("placeholder", "Select a Minecraft account");
        select.addProperty("min_values", 1);
        select.addProperty("max_values", 1);

        JsonArray options = new JsonArray();
        clients.stream().limit(25).forEach(client -> {
            JsonObject option = new JsonObject();
            option.addProperty("label", client.accountName());
            option.addProperty("value", client.id());
            options.add(option);
        });

        select.add("options", options);
        components.add(select);
        row.add("components", components);
        rows.add(row);

        return rows;
    }

    private JsonArray buildWarpComponents(AetherClientInstance client) {
        JsonArray rows = new JsonArray();

        JsonObject selectRow = new JsonObject();
        selectRow.addProperty("type", 1);
        JsonArray selectComponents = new JsonArray();
        JsonObject select = new JsonObject();
        select.addProperty("type", 3);
        select.addProperty("custom_id", "aether:warp:" + client.id());
        select.addProperty("placeholder", "Choose a warp");
        select.addProperty("min_values", 1);
        select.addProperty("max_values", 1);

        JsonArray options = new JsonArray();
        for (String warpCommand : WARP_COMMANDS) {
            JsonObject option = new JsonObject();
            option.addProperty("label", warpCommand);
            option.addProperty("value", warpCommand);
            options.add(option);
        }
        select.add("options", options);
        selectComponents.add(select);
        selectRow.add("components", selectComponents);
        rows.add(selectRow);

        JsonObject backRow = new JsonObject();
        backRow.addProperty("type", 1);
        JsonArray buttons = new JsonArray();
        buttons.add(buildButton("Back", "aether:command:" + client.id() + ":back", BUTTON_STYLE_GRAY, false));
        backRow.add("components", buttons);
        rows.add(backRow);
        return rows;
    }

    private JsonObject buildRemoteEmbed(AetherClientInstance client, String statusLine) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "Remote Control: " + client.accountName());
        embed.addProperty("description", statusLine + "\n\nChoose an action for this player.");
        embed.addProperty("color", 0x5865F2);
        JsonObject footer = new JsonObject();
        addBrandFooter(footer);
        embed.add("footer", footer);

        return embed;
    }

    private JsonArray buildRemoteButtons(AetherClientInstance client) {
        JsonArray rows = new JsonArray();
        JsonObject row = new JsonObject();
        row.addProperty("type", 1);

        JsonArray buttons = new JsonArray();
        buttons.add(buildButton("Start", "aether:command:" + client.id() + ":start", BUTTON_STYLE_GRAY, false));
        buttons.add(buildButton("Stop", "aether:command:" + client.id() + ":stop", BUTTON_STYLE_GRAY, false));
        buttons.add(buildButton("Status", "aether:command:" + client.id() + ":status", BUTTON_STYLE_GRAY, false));
        buttons.add(buildButton("Warps", "aether:command:" + client.id() + ":warps", BUTTON_STYLE_GRAY, false));
        buttons.add(buildButton("Chat", "aether:command:" + client.id() + ":chat", BUTTON_STYLE_GRAY, false));
        row.add("components", buttons);
        rows.add(row);

        JsonObject safetyRow = new JsonObject();
        safetyRow.addProperty("type", 1);
        JsonArray safetyButtons = new JsonArray();
        safetyButtons.add(buildButton("Back", "aether:command:" + client.id() + ":end", BUTTON_STYLE_GRAY, false));
        safetyButtons.add(buildButton("Connect", "aether:command:" + client.id() + ":connect", BUTTON_STYLE_GRAY, false));
        safetyButtons.add(buildButton("Disconnect", "aether:command:" + client.id() + ":disconnect", BUTTON_STYLE_GRAY, false));
        safetyButtons.add(buildButton("Panic", "aether:command:" + client.id() + ":panic", BUTTON_STYLE_GRAY, false));
        safetyRow.add("components", safetyButtons);
        rows.add(safetyRow);
        return rows;
    }

    private JsonObject buildButton(String label, String customId, int style, boolean disabled) {
        JsonObject button = new JsonObject();
        button.addProperty("type", 2);
        button.addProperty("label", label);
        button.addProperty("style", style);
        button.addProperty("custom_id", customId);
        button.addProperty("disabled", disabled);
        return button;
    }

    private void updateInteractionMessage(String interactionId, String interactionToken, JsonObject embed, JsonArray components) {
        JsonObject body = new JsonObject();
        body.addProperty("type", 7);

        JsonObject data = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        data.add("embeds", embeds);
        data.add("components", components);
        body.add("data", data);

        postInteractionCallback(interactionId, interactionToken, body);
    }

    private void respondInteractionMessage(String interactionId, String interactionToken, JsonObject embed, JsonArray components) {
        JsonObject body = new JsonObject();
        body.addProperty("type", 4);

        JsonObject data = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        data.add("embeds", embeds);
        data.add("components", components);
        body.add("data", data);

        postInteractionCallback(interactionId, interactionToken, body);
    }

    private void deferInteractionUpdate(String interactionId, String interactionToken) {
        JsonObject body = new JsonObject();
        body.addProperty("type", 6);
        postInteractionCallback(interactionId, interactionToken, body);
    }

    private void deferInteractionChannelMessage(String interactionId, String interactionToken) {
        JsonObject body = new JsonObject();
        body.addProperty("type", 5);
        postInteractionCallback(interactionId, interactionToken, body);
    }

    private void postInteractionCallback(String interactionId, String interactionToken, JsonObject body) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(DISCORD_API + "/interactions/" + interactionId + "/" + interactionToken + "/callback"))
                .timeout(Duration.ofSeconds(10));

        HttpRequest request;
        Integer responseType = readJsonInt(body, "type");
        if (responseType != null && (responseType == 4 || responseType == 7) && hasBrandImage()) {
            JsonObject data = readJsonObject(body, "data");
            if (data != null) {
                data.add("attachments", attachmentsJson(List.of(brandUploadFile(0))));
            }

            String boundary = "AetherBoundary" + Instant.now().toEpochMilli();
            request = requestBuilder
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(ofMultipartData(boundary, body.toString(), List.of(brandUploadFile(0))))
                    .build();
        } else {
            request = requestBuilder
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
        }

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        LOGGER.error("Failed to respond to Discord interaction", throwable);
                        return;
                    }

                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        LOGGER.error("Discord interaction response failed: HTTP {} {}", response.statusCode(), response.body());
                    }
                });
    }

    private void sendStatusScreenshot(String channelId, AetherClientInstance client) {
        executeCommand(client, "status");

        HttpRequest screenshotRequest = HttpRequest.newBuilder()
                .uri(URI.create(client.controlUrl("screenshot")))
                .timeout(Duration.ofSeconds(8))
                .header("X-Remote-Auth", AetherRemoteControlMod.AUTH_TOKEN)
                .GET()
                .build();

        try {
            HttpResponse<byte[]> response = httpClient.send(screenshotRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                sendPlainChannelMessage(channelId, "Could not capture status screenshot for `" + client.accountName() + "`.");
                return;
            }

            sendScreenshotChannelMessage(channelId, client, response.body());
        } catch (Exception exception) {
            LOGGER.error("Failed to capture status screenshot for {}", client.id(), exception);
            sendPlainChannelMessage(channelId, "Could not capture status screenshot for `" + client.accountName() + "`.");
        }
    }

    private void sendFailsafeAlertMessage(String channelId, AetherClientInstance client, String triggerMessage) {
        byte[] imageBytes = captureScreenshot(client);
        if (imageBytes.length == 0) {
            sendPlainChannelMessage(channelId, "@everyone Alearted for staff check - " + client.accountName());
            return;
        }

        String boundary = "AetherBoundary" + Instant.now().toEpochMilli();
        JsonObject payload = new JsonObject();
        payload.addProperty("content", "@everyone");

        JsonObject allowedMentions = new JsonObject();
        JsonArray parse = new JsonArray();
        parse.add("everyone");
        allowedMentions.add("parse", parse);
        payload.add("allowed_mentions", allowedMentions);

        JsonArray embeds = new JsonArray();
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "Alearted for staff check");
        embed.addProperty("description", "**Client:** `" + client.accountName() + "`\n**Trigger:** " + sanitizeEmbedText(triggerMessage));
        embed.addProperty("color", 0xED4245);
        JsonObject image = new JsonObject();
        image.addProperty("url", "attachment://failsafe.png");
        embed.add("image", image);
        JsonObject footer = new JsonObject();
        addBrandFooter(footer);
        embed.add("footer", footer);
        embeds.add(embed);
        payload.add("embeds", embeds);

        List<UploadFile> files = new ArrayList<>();
        files.add(new UploadFile("files[0]", "failsafe.png", "image/png", imageBytes));
        if (hasBrandImage()) {
            files.add(brandUploadFile(1));
        }
        payload.add("attachments", attachmentsJson(files));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DISCORD_API + "/channels/" + channelId + "/messages"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bot " + config.discordBotToken)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(ofMultipartData(boundary, payload.toString(), files))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        LOGGER.error("Failed to send failsafe alert to Discord", throwable);
                        return;
                    }

                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        LOGGER.error("Discord failsafe alert failed: HTTP {} {}", response.statusCode(), response.body());
                    }
                });
    }

    private void editControlMessageWithScreenshot(
            String channelId,
            String messageId,
            AetherClientInstance client,
            String statusLine,
            JsonArray components
    ) {
        if (channelId.isBlank() || messageId.isBlank()) {
            return;
        }

        byte[] imageBytes = captureScreenshot(client);
        if (imageBytes.length == 0) {
            editControlMessage(channelId, messageId, client, statusLine, components);
            return;
        }

        String boundary = "AetherBoundary" + Instant.now().toEpochMilli();
        JsonObject payload = new JsonObject();
        JsonArray embeds = new JsonArray();
        JsonObject embed = buildRemoteEmbed(client, statusLine);
        JsonObject image = new JsonObject();
        image.addProperty("url", "attachment://control.png");
        embed.add("image", image);
        embeds.add(embed);
        payload.add("embeds", embeds);
        payload.add("components", components);

        List<UploadFile> files = new ArrayList<>();
        files.add(new UploadFile("files[0]", "control.png", "image/png", imageBytes));
        if (hasBrandImage()) {
            files.add(brandUploadFile(1));
        }
        payload.add("attachments", attachmentsJson(files));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DISCORD_API + "/channels/" + channelId + "/messages/" + messageId))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bot " + config.discordBotToken)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .method("PATCH", ofMultipartData(boundary, payload.toString(), files))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        LOGGER.error("Failed to edit control screenshot embed", throwable);
                        return;
                    }

                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        LOGGER.error("Discord control screenshot edit failed: HTTP {} {}", response.statusCode(), response.body());
                    }
                });
    }

    private void editControlMessage(
            String channelId,
            String messageId,
            AetherClientInstance client,
            String statusLine,
            JsonArray components
    ) {
        JsonObject payload = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(buildRemoteEmbed(client, statusLine));
        payload.add("embeds", embeds);
        payload.add("components", components);
        payload.add("attachments", new JsonArray());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DISCORD_API + "/channels/" + channelId + "/messages/" + messageId))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bot " + config.discordBotToken)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    private void editOriginalSlashResponseWithScreenshot(String interactionToken, AetherClientInstance client, String statusLine) {
        byte[] imageBytes = captureScreenshot(client);
        if (imageBytes.length == 0) {
            editOriginalSlashResponse(interactionToken, client, statusLine);
            return;
        }

        String boundary = "AetherBoundary" + Instant.now().toEpochMilli();
        JsonObject payload = new JsonObject();
        JsonArray embeds = new JsonArray();
        JsonObject embed = buildRemoteEmbed(client, statusLine);
        JsonObject image = new JsonObject();
        image.addProperty("url", "attachment://control.png");
        embed.add("image", image);
        embeds.add(embed);
        payload.add("embeds", embeds);

        List<UploadFile> files = new ArrayList<>();
        files.add(new UploadFile("files[0]", "control.png", "image/png", imageBytes));
        if (hasBrandImage()) {
            files.add(brandUploadFile(1));
        }
        payload.add("attachments", attachmentsJson(files));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DISCORD_API + "/webhooks/" + config.discordApplicationId + "/" + interactionToken + "/messages/@original"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .method("PATCH", ofMultipartData(boundary, payload.toString(), files))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        LOGGER.error("Failed to edit slash command screenshot embed", throwable);
                        return;
                    }

                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        LOGGER.error("Discord slash screenshot edit failed: HTTP {} {}", response.statusCode(), response.body());
                    }
                });
    }

    private void editOriginalSlashResponse(String interactionToken, AetherClientInstance client, String statusLine) {
        JsonObject payload = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(buildRemoteEmbed(client, statusLine));
        payload.add("embeds", embeds);
        payload.add("attachments", new JsonArray());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DISCORD_API + "/webhooks/" + config.discordApplicationId + "/" + interactionToken + "/messages/@original"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    private byte[] captureScreenshot(AetherClientInstance client) {
        HttpRequest screenshotRequest = HttpRequest.newBuilder()
                .uri(URI.create(client.controlUrl("screenshot")))
                .timeout(Duration.ofSeconds(8))
                .header("X-Remote-Auth", AetherRemoteControlMod.AUTH_TOKEN)
                .GET()
                .build();

        try {
            HttpResponse<byte[]> response = httpClient.send(screenshotRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new byte[0];
            }

            return response.body();
        } catch (Exception exception) {
            LOGGER.error("Failed to capture screenshot for {}", client.id(), exception);
            return new byte[0];
        }
    }

    private void sendScreenshotChannelMessage(String channelId, AetherClientInstance client, byte[] imageBytes) {
        String boundary = "AetherBoundary" + Instant.now().toEpochMilli();
        JsonObject payload = new JsonObject();

        JsonArray embeds = new JsonArray();
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "Status: " + client.accountName());
        embed.addProperty("description", "Latest game view from `" + client.accountName() + "`.");
        embed.addProperty("color", 0x57F287);
        JsonObject image = new JsonObject();
        image.addProperty("url", "attachment://status.png");
        embed.add("image", image);
        JsonObject footer = new JsonObject();
        addBrandFooter(footer);
        embed.add("footer", footer);
        embeds.add(embed);
        payload.add("embeds", embeds);
        List<UploadFile> files = new ArrayList<>();
        files.add(new UploadFile("files[0]", "status.png", "image/png", imageBytes));
        if (hasBrandImage()) {
            files.add(brandUploadFile(1));
        }
        payload.add("attachments", attachmentsJson(files));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DISCORD_API + "/channels/" + channelId + "/messages"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bot " + config.discordBotToken)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(ofMultipartData(boundary, payload.toString(), files))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        LOGGER.error("Failed to send status screenshot to Discord", throwable);
                        return;
                    }

                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        LOGGER.error("Discord status screenshot failed: HTTP {} {}", response.statusCode(), response.body());
                    }
                });
    }

    private HttpRequest.BodyPublisher ofMultipartData(String boundary, String payloadJson, List<UploadFile> files) {
        List<byte[]> parts = new ArrayList<>();
        parts.add(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"payload_json\"\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + payloadJson + "\r\n").getBytes(StandardCharsets.UTF_8));

        for (UploadFile file : files) {
            parts.add(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + file.fieldName() + "\"; filename=\"" + file.filename() + "\"\r\n"
                    + "Content-Type: " + file.contentType() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            parts.add(file.bytes());
            parts.add("\r\n".getBytes(StandardCharsets.UTF_8));
        }

        parts.add(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return HttpRequest.BodyPublishers.ofByteArrays(parts);
    }

    private boolean hasBrandImage() {
        return brandImageBytes.length > 0;
    }

    private UploadFile brandUploadFile(int index) {
        return new UploadFile("files[" + index + "]", BRAND_ATTACHMENT, "image/png", brandImageBytes);
    }

    private JsonArray attachmentsJson(List<UploadFile> files) {
        JsonArray attachments = new JsonArray();
        for (int index = 0; index < files.size(); index++) {
            JsonObject attachment = new JsonObject();
            attachment.addProperty("id", index);
            attachment.addProperty("filename", files.get(index).filename());
            attachments.add(attachment);
        }
        return attachments;
    }

    private void addBrandFooter(JsonObject footer) {
        footer.addProperty("text", "Aether Client - " + formattedFooterDate());
        if (hasBrandImage()) {
            footer.addProperty("icon_url", "attachment://" + BRAND_ATTACHMENT);
        }
    }

    private String formattedFooterDate() {
        return FOOTER_DATE_FORMAT
                .format(ZonedDateTime.now(ZoneId.systemDefault()))
                .toLowerCase();
    }

    private byte[] loadBrandImageBytes() {
        try (InputStream inputStream = DiscordBotService.class.getResourceAsStream(BRAND_RESOURCE)) {
            return inputStream == null ? new byte[0] : inputStream.readAllBytes();
        } catch (IOException exception) {
            LOGGER.warn("Failed to load Aether branding image", exception);
            return new byte[0];
        }
    }

    private void sendPlainChannelMessage(String channelId, String content) {
        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DISCORD_API + "/channels/" + channelId + "/messages"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bot " + config.discordBotToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding());
    }

    private void rememberControlChannel(String channelId) {
        if (channelId != null && !channelId.isBlank()) {
            recentControlChannelId = channelId;
            AetherAlertChannelStore.save(channelId);
        }
    }

    private String sanitizeEmbedText(String value) {
        if (value == null || value.isBlank()) {
            return "Aether failsafe triggered.";
        }

        String sanitized = value
                .replace("@", "@\u200B")
                .replace("\n", " ")
                .replace("\r", " ")
                .trim();
        return sanitized.length() > 500 ? sanitized.substring(0, 500) : sanitized;
    }

    private String readInteractionUserId(JsonObject interaction) {
        JsonObject member = readJsonObject(interaction, "member");
        JsonObject memberUser = member == null ? null : readJsonObject(member, "user");
        String memberUserId = memberUser == null ? "" : readJsonString(memberUser, "id");
        if (!memberUserId.isBlank()) {
            return memberUserId;
        }

        JsonObject user = readJsonObject(interaction, "user");
        return user == null ? "" : readJsonString(user, "id");
    }

    private String readFirstSelectedValue(JsonObject data) {
        JsonArray values = data == null ? null : readJsonArray(data, "values");
        if (values == null || values.isEmpty()) {
            return "";
        }

        JsonElement firstValue = values.get(0);
        return firstValue == null || firstValue.isJsonNull() ? "" : firstValue.getAsString();
    }

    private String readModalTextValue(JsonObject data, String targetCustomId) {
        JsonArray rows = data == null ? null : readJsonArray(data, "components");
        if (rows == null) {
            return "";
        }

        for (JsonElement rowElement : rows) {
            if (!rowElement.isJsonObject()) {
                continue;
            }

            JsonArray components = readJsonArray(rowElement.getAsJsonObject(), "components");
            if (components == null) {
                continue;
            }

            for (JsonElement componentElement : components) {
                if (!componentElement.isJsonObject()) {
                    continue;
                }

                JsonObject component = componentElement.getAsJsonObject();
                if (targetCustomId.equals(readJsonString(component, "custom_id"))) {
                    return readJsonString(component, "value");
                }
            }
        }

        return "";
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

    private static JsonArray readJsonArray(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
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

    private static boolean readJsonBoolean(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return false;
        }

        try {
            return element.getAsBoolean();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
