package com.yario.aetherremote;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

final class AetherClientCommands {
    private AetherClientCommands() {
    }

    static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("aetherremotecontrol")
                        .executes(context -> {
                            AetherConfigScreen.open();
                            MinecraftClientBridge.sendClientFeedback("Opened Aether Remote Control config.");
                            return 1;
                        })));
    }
}
