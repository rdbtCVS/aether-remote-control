package com.yario.aetherremote;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

final class AetherKeybinds {
    private static KeyMapping openConfigKey;

    private AetherKeybinds() {
    }

    static void register() {
        openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.aether_remote_control.open_config",
                GLFW.GLFW_KEY_F10,
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("aether_remote_control", "controls"))
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openConfigKey.consumeClick()) {
                if (client.screen instanceof AetherConfigScreen) {
                    client.setScreen(null);
                } else {
                    client.setScreen(new AetherConfigScreen(AetherRemoteConfig.load()));
                }
            }
        });
    }
}
