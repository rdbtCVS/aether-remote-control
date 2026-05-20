package com.yario.aetherremote;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class AetherConfigScreen extends Screen {
    private static final int FIELD_WIDTH = 620;
    private static final int FIELD_HEIGHT = 20;
    private static final int LABEL_TO_FIELD_GAP = 14;
    private static final int ROW_GAP = 58;
    private static final int TOKEN_MAX_LENGTH = 512;

    private final AetherRemoteConfig config;

    private EditBox tokenField;
    private EditBox applicationIdField;
    private EditBox guildIdField;

    AetherConfigScreen(AetherRemoteConfig config) {
        super(text("Aether Remote Control"));
        this.config = config;
    }

    static void openIfConfigMissing() {
        if (AetherRemoteConfig.load().isDiscordConfigured()) {
            return;
        }

        Thread opener = new Thread(() -> {
            try {
                Thread.sleep(4000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }

            Minecraft client = Minecraft.getInstance();
            client.execute(() -> {
                if (client.screen == null && !AetherRemoteConfig.load().isDiscordConfigured()) {
                    client.setScreen(new AetherConfigScreen(AetherRemoteConfig.load()));
                }
            });
        }, "Aether Config Screen Opener");
        opener.setDaemon(true);
        opener.start();
    }

    static void open() {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.setScreen(new AetherConfigScreen(AetherRemoteConfig.load())));
    }

    @Override
    protected void init() {
        int fieldX = fieldX();
        int y = firstRowY();

        tokenField = new EditBox(this.font, fieldX, y, FIELD_WIDTH, FIELD_HEIGHT, text("Discord Bot Token"));
        tokenField.setMaxLength(TOKEN_MAX_LENGTH);
        tokenField.setValue(config.discordBotToken);
        tokenField.setSuggestion("");
        addRenderableWidget(tokenField);

        applicationIdField = new EditBox(this.font, fieldX, y + ROW_GAP, FIELD_WIDTH, FIELD_HEIGHT, text("Application ID"));
        applicationIdField.setMaxLength(32);
        applicationIdField.setValue(config.discordApplicationId);
        applicationIdField.setSuggestion("");
        addRenderableWidget(applicationIdField);

        guildIdField = new EditBox(this.font, fieldX, y + ROW_GAP * 2, FIELD_WIDTH, FIELD_HEIGHT, text("Server ID"));
        guildIdField.setMaxLength(32);
        guildIdField.setValue(config.discordGuildId);
        guildIdField.setSuggestion("");
        addRenderableWidget(guildIdField);

        int buttonY = y + ROW_GAP * 3 + 20;
        addRenderableWidget(Button.builder(text("Save & Connect"), button -> saveAndConnect())
                .bounds(fieldX, buttonY, 260, 20)
                .build());

        addRenderableWidget(Button.builder(text("Close"), button -> this.minecraft.setScreen(null))
                .bounds(fieldX + 280, buttonY, 260, 20)
                .build());
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, this.width, this.height, 0xE0101010);

        int fieldX = fieldX();
        int y = firstRowY();

        drawText(context, "Aether Remote Control", fieldX, 28, 0xFFFFFFFF);
        drawText(context, "Fill these in from the Discord Developer Portal. Press Esc or F10 to close.", fieldX, 46, 0xFFD8D8D8);
        drawText(context, "Type /aether panel in Discord to open the embed control panel after saving.", fieldX, 60, 0xFFD8D8D8);

        drawText(context, "1. Discord Bot Token", fieldX, y - LABEL_TO_FIELD_GAP, 0xFFFFFFFF);
        drawText(context, "2. Application ID / Client ID (optional)", fieldX, y + ROW_GAP - LABEL_TO_FIELD_GAP, 0xFFFFFFFF);
        drawText(context, "3. Server ID / Guild ID", fieldX, y + ROW_GAP * 2 - LABEL_TO_FIELD_GAP, 0xFFFFFFFF);

        super.render(context, mouseX, mouseY, delta);
        drawText(context, "1. Discord Bot Token", fieldX, y - LABEL_TO_FIELD_GAP, 0xFFFFFFFF);
        drawText(context, "2. Application ID / Client ID (optional)", fieldX, y + ROW_GAP - LABEL_TO_FIELD_GAP, 0xFFFFFFFF);
        drawText(context, "3. Server ID / Guild ID", fieldX, y + ROW_GAP * 2 - LABEL_TO_FIELD_GAP, 0xFFFFFFFF);
        drawText(context, "Paste the full bot token into box 1. Token field limit: 512 characters.", fieldX, y + ROW_GAP * 3 + 4, 0xFFFFCC66);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    private void saveAndConnect() {
        config.discordBotToken = tokenField.getValue().trim();
        config.discordApplicationId = applicationIdField.getValue().trim();
        config.discordGuildId = guildIdField.getValue().trim();
        config.save();

        MinecraftClientBridge.sendClientFeedback("§a[Remote Control] Discord config saved. Connecting bot...");
        AetherRemoteControlMod.restartDiscordBotFromConfig();
        this.minecraft.setScreen(null);
    }

    private static Component text(String value) {
        return Component.nullToEmpty(value);
    }

    private void drawText(GuiGraphics context, String value, int x, int y, int color) {
        context.drawString(this.font, value, x, y, color, true);
    }

    private int fieldX() {
        return Math.max(32, this.width / 2 - FIELD_WIDTH / 2);
    }

    private int firstRowY() {
        return 112;
    }
}
