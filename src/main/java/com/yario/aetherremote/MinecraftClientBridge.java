package com.yario.aetherremote;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class MinecraftClientBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(AetherRemoteControlMod.MOD_ID);
    private static final long MIN_REMOTE_INPUT_INTERVAL_MILLIS = 1250L;

    private static final String INTERMEDIARY = "intermediary";
    private static final MappingResolver MAPPINGS = FabricLoader.getInstance().getMappingResolver();

    private static final String MINECRAFT_CLIENT = mapClass("net.minecraft.class_310");
    private static final String LOCAL_PLAYER = mapClass("net.minecraft.class_746");
    private static final String CLIENT_PACKET_LISTENER = mapClass("net.minecraft.class_634");
    private static final String COMPONENT = mapClass("net.minecraft.class_2561");
    private static long nextRemoteInputAtMillis;

    private MinecraftClientBridge() {
    }

    static void executeSlashCommand(String commandWithoutSlash, String feedbackMessage) {
        executeOnClientThread(() -> {
            try {
                Object client = getMinecraftClient();
                if (!isReadyForRemoteInput(client)) {
                    sendClientFeedback("§e[Remote Control] Skipped command while Minecraft is changing screens/worlds.");
                    return;
                }

                if (!claimRemoteInputSlot()) {
                    sendClientFeedback("§e[Remote Control] Skipped command because another remote input just ran.");
                    return;
                }

                Object player = getPlayer(client);
                if (player == null) {
                    return;
                }

                Object connection = getConnection(client);
                if (connection == null) {
                    return;
                }

                invokeSendCommand(connection, commandWithoutSlash);
                sendMessage(player, feedbackMessage);
            } catch (ReflectiveOperationException exception) {
                LOGGER.error("Failed to execute remote Aether command", exception);
            }
        });
    }

    static void executeChatMessage(String message, String feedbackMessage) {
        executeOnClientThread(() -> {
            try {
                Object client = getMinecraftClient();
                if (!isReadyForRemoteInput(client)) {
                    sendClientFeedback("§e[Remote Control] Skipped chat while Minecraft is changing screens/worlds.");
                    return;
                }

                if (!claimRemoteInputSlot()) {
                    sendClientFeedback("§e[Remote Control] Skipped chat because another remote input just ran.");
                    return;
                }

                Object player = getPlayer(client);
                if (player == null) {
                    return;
                }

                Object connection = getConnection(client);
                if (connection == null) {
                    return;
                }

                invokeSendChatMessage(connection, message);
                sendMessage(player, feedbackMessage);
            } catch (ReflectiveOperationException exception) {
                LOGGER.error("Failed to execute remote Aether chat message", exception);
            }
        });
    }

    static void sendClientFeedback(String message) {
        executeOnClientThread(() -> {
            try {
                Object client = getMinecraftClient();
                Object player = getPlayer(client);
                if (player != null) {
                    sendMessage(player, message);
                }
            } catch (ReflectiveOperationException exception) {
                LOGGER.error("Failed to send remote-control feedback message", exception);
            }
        });
    }

    static void disconnectFromServer(String feedbackMessage) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            Object player = client.player;
            if (player != null) {
                try {
                    sendMessage(player, feedbackMessage);
                } catch (ReflectiveOperationException exception) {
                    LOGGER.warn("Failed to send disconnect feedback before disconnecting", exception);
                }
            }

            client.disconnect(new TitleScreen(), false);
        });
    }

    static void connectToServer(String address) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            ServerData serverData = new ServerData(address, address, ServerData.Type.OTHER);
            ConnectScreen.startConnecting(
                    new TitleScreen(),
                    client,
                    ServerAddress.parseString(address),
                    serverData,
                    false,
                    null
            );
        });
    }

    static void panicCrash() {
        Runtime.getRuntime().halt(1);
    }

    static byte[] captureScreenshot() throws IOException {
        CompletableFuture<byte[]> screenshot = new CompletableFuture<>();
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> Screenshot.takeScreenshot(client.getMainRenderTarget(), image -> {
            Path tempFile = null;
            try (NativeImage nativeImage = image) {
                tempFile = Files.createTempFile("aether-remote-status-", ".png");
                nativeImage.writeToFile(tempFile);
                screenshot.complete(Files.readAllBytes(tempFile));
            } catch (IOException exception) {
                screenshot.completeExceptionally(exception);
            } finally {
                if (tempFile != null) {
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (IOException exception) {
                        LOGGER.warn("Failed to delete temporary Aether status screenshot", exception);
                    }
                }
            }
        }));

        try {
            return screenshot.get(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IOException("Timed out while capturing Minecraft screenshot", exception);
        }
    }

    private static boolean isReadyForRemoteInput(Object client) throws ReflectiveOperationException {
        if (getPlayer(client) == null || getConnection(client) == null) {
            return false;
        }

        Object level = getLevel(client);
        if (level == null) {
            return false;
        }

        Object screen = getCurrentScreen(client);
        if (screen == null) {
            return true;
        }

        String screenClassName = screen.getClass().getName().toLowerCase();
        return !screenClassName.contains("connect")
                && !screenClassName.contains("disconnect")
                && !screenClassName.contains("progress")
                && !screenClassName.contains("receiving")
                && !screenClassName.contains("download")
                && !screenClassName.contains("report");
    }

    private static boolean claimRemoteInputSlot() {
        long now = System.currentTimeMillis();
        if (now < nextRemoteInputAtMillis) {
            return false;
        }

        nextRemoteInputAtMillis = now + MIN_REMOTE_INPUT_INTERVAL_MILLIS;
        return true;
    }

    private static void executeOnClientThread(Runnable task) {
        try {
            Object client = getMinecraftClient();
            Method execute = client.getClass().getMethod("execute", Runnable.class);
            execute.invoke(client, task);
        } catch (ReflectiveOperationException exception) {
            LOGGER.error("Failed to schedule task on Minecraft client thread", exception);
        }
    }

    private static Object getMinecraftClient() throws ReflectiveOperationException {
        Class<?> clientClass = Class.forName(MINECRAFT_CLIENT);
        String methodName = MAPPINGS.mapMethodName(
                INTERMEDIARY,
                "net.minecraft.class_310",
                "method_1551",
                "()Lnet/minecraft/class_310;"
        );

        return clientClass.getMethod(methodName).invoke(null);
    }

    private static Object getPlayer(Object client) throws ReflectiveOperationException {
        Field playerField = client.getClass().getField(MAPPINGS.mapFieldName(
                INTERMEDIARY,
                "net.minecraft.class_310",
                "field_1724",
                "Lnet/minecraft/class_746;"
        ));

        return playerField.get(client);
    }

    private static Object getConnection(Object client) throws ReflectiveOperationException {
        Method getConnection = client.getClass().getMethod(MAPPINGS.mapMethodName(
                INTERMEDIARY,
                "net.minecraft.class_310",
                "method_1562",
                "()Lnet/minecraft/class_634;"
        ));

        return getConnection.invoke(client);
    }

    private static Object getLevel(Object client) throws ReflectiveOperationException {
        Field levelField = client.getClass().getField(MAPPINGS.mapFieldName(
                INTERMEDIARY,
                "net.minecraft.class_310",
                "field_1687",
                "Lnet/minecraft/class_638;"
        ));

        return levelField.get(client);
    }

    private static Object getCurrentScreen(Object client) throws ReflectiveOperationException {
        Field screenField = client.getClass().getField(MAPPINGS.mapFieldName(
                INTERMEDIARY,
                "net.minecraft.class_310",
                "field_1755",
                "Lnet/minecraft/class_437;"
        ));

        return screenField.get(client);
    }

    private static void invokeSendCommand(Object connection, String commandWithoutSlash) throws ReflectiveOperationException {
        Method sendCommand = connection.getClass().getMethod(MAPPINGS.mapMethodName(
                INTERMEDIARY,
                "net.minecraft.class_634",
                "method_45730",
                "(Ljava/lang/String;)V"
        ), String.class);

        sendCommand.invoke(connection, commandWithoutSlash);
    }

    private static void invokeSendChatMessage(Object connection, String message) throws ReflectiveOperationException {
        Method sendChatMessage = connection.getClass().getMethod(MAPPINGS.mapMethodName(
                INTERMEDIARY,
                "net.minecraft.class_634",
                "method_45729",
                "(Ljava/lang/String;)V"
        ), String.class);

        sendChatMessage.invoke(connection, message);
    }

    private static void sendMessage(Object player, String message) throws ReflectiveOperationException {
        Object component = createTextComponent(message);
        Method sendMessage = player.getClass().getMethod(MAPPINGS.mapMethodName(
                INTERMEDIARY,
                "net.minecraft.class_746",
                "method_7353",
                "(Lnet/minecraft/class_2561;Z)V"
        ), Class.forName(COMPONENT), boolean.class);

        sendMessage.invoke(player, component, false);
    }

    private static Object createTextComponent(String message) throws ReflectiveOperationException {
        Class<?> componentClass = Class.forName(COMPONENT);
        Method literal = componentClass.getMethod(MAPPINGS.mapMethodName(
                INTERMEDIARY,
                "net.minecraft.class_2561",
                "method_30163",
                "(Ljava/lang/String;)Lnet/minecraft/class_2561;"
        ), String.class);

        return literal.invoke(null, message);
    }

    private static String mapClass(String intermediaryClassName) {
        return MAPPINGS.mapClassName(INTERMEDIARY, intermediaryClassName);
    }
}
