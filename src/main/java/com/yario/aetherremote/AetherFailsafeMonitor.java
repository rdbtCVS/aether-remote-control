package com.yario.aetherremote;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

import java.util.Locale;

final class AetherFailsafeMonitor {
    private static long nextAlertAtMillis;

    private AetherFailsafeMonitor() {
    }

    static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> inspect(message));
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> inspect(message));
    }

    private static void inspect(Component message) {
        String plainMessage = message.getString();
        String normalizedMessage = plainMessage.toLowerCase(Locale.ROOT);
        if (!normalizedMessage.contains("failsafe")) {
            return;
        }

        if (!normalizedMessage.contains("failsafe triggered")
                && !normalizedMessage.contains("farming exp text disappeared")) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now < nextAlertAtMillis) {
            return;
        }

        nextAlertAtMillis = now + 60_000L;
        AetherRemoteControlMod.reportFailsafeAlert(plainMessage);
    }
}
