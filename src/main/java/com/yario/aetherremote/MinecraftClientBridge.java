package com.yario.aetherremote;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class MinecraftClientBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(AetherRemoteControlMod.MOD_ID);

    private static final String INTERMEDIARY = "intermediary";
    private static final MappingResolver MAPPINGS = FabricLoader.getInstance().getMappingResolver();

    private static final String MINECRAFT_CLIENT = mapClass("net.minecraft.class_310");
    private static final String LOCAL_PLAYER = mapClass("net.minecraft.class_746");
    private static final String CLIENT_PACKET_LISTENER = mapClass("net.minecraft.class_634");
    private static final String COMPONENT = mapClass("net.minecraft.class_2561");

    private MinecraftClientBridge() {
    }

    static void executeSlashCommand(String commandWithoutSlash, String feedbackMessage) {
        executeOnClientThread(() -> {
            try {
                Object client = getMinecraftClient();
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
