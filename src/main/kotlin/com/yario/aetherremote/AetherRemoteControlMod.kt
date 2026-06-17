package com.yario.aetherremote

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.NativeImage
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AetherRemoteControlMod : ClientModInitializer {
    override fun onInitializeClient() {
        instance = this
        bot = DiscordBot(Config.load()).also { if (it.configured) it.start() }
        FailsafeMonitor.register()
        registerConfigOpeners()
        ConfigScreen.openIfMissing()
        Runtime.getRuntime().addShutdownHook(Thread({ bot?.stop() }, "Aether Remote Shutdown"))
    }

    private fun restart() {
        bot?.stop()
        bot = DiscordBot(Config.load()).also { if (it.configured) it.start() }
    }

    companion object {
        const val MOD_ID = "aether_remote_control"
        val LOGGER = LoggerFactory.getLogger(MOD_ID)
        private var instance: AetherRemoteControlMod? = null
        private var bot: DiscordBot? = null

        fun restartFromConfig() = instance?.restart()
        fun failsafe(message: String) = bot?.sendFailsafe(message)
    }
}

private data class Config(
    var discordBotToken: String = "",
    var discordGuildId: String = "",
    var channelId: String = ""
) {
    val configured: Boolean get() = discordBotToken.isNotBlank() && discordGuildId.isNotBlank() && channelId.isNotBlank()

    fun save() {
        Files.createDirectories(path.parent)
        val json = JsonObject().apply {
            addProperty("discordBotToken", discordBotToken)
            addProperty("discordGuildId", discordGuildId)
            addProperty("channelId", channelId)
        }
        Files.writeString(path, json.toString(), StandardCharsets.UTF_8)
    }

    companion object {
        private val path: Path = FabricLoader.getInstance().configDir.resolve("aether-remote-control.json")

        fun load(): Config {
            if (!Files.exists(path)) return Config()
            return runCatching {
                val json = JsonParser.parseString(Files.readString(path)).asJsonObject
                Config(
                    json["discordBotToken"]?.asString.orEmpty(),
                    json["discordGuildId"]?.asString.orEmpty(),
                    json["channelId"]?.asString
                        ?: json["controlChannels"]?.asJsonObject?.get(Minecraft.getInstance().user.name)?.asString
                        ?: json["alertChannelId"]?.asString
                        ?: ""
                )
            }.getOrElse {
                AetherRemoteControlMod.LOGGER.warn("Failed to read Aether config", it)
                Config()
            }
        }
    }
}

private fun registerConfigOpeners() {
    ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
        dispatcher.register(ClientCommandManager.literal("aetherremotecontrol").executes {
            ConfigScreen.open()
            1
        })
    }

    val key = KeyBindingHelper.registerKeyBinding(
        KeyMapping(
            "key.aether_remote_control.open_config",
            GLFW.GLFW_KEY_F10,
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(AetherRemoteControlMod.MOD_ID, "controls"))
        )
    )
    ClientTickEvents.END_CLIENT_TICK.register { client ->
        while (key.consumeClick()) {
            client.setScreen(if (client.screen is ConfigScreen) null else ConfigScreen(Config.load()))
        }
    }
}

private class ConfigScreen(private val config: Config) : Screen(Component.literal("Aether Remote Control")) {
    private lateinit var token: EditBox
    private lateinit var guild: EditBox
    private lateinit var channel: EditBox

    override fun init() {
        val x = (width / 2 - 310).coerceAtLeast(32)
        token = EditBox(font, x, 112, 620, 20, Component.literal("Discord Bot Token")).also {
            it.setMaxLength(512)
            it.setValue(config.discordBotToken)
            addRenderableWidget(it)
        }
        guild = EditBox(font, x, 170, 620, 20, Component.literal("Discord Server ID")).also {
            it.setMaxLength(32)
            it.setValue(config.discordGuildId)
            addRenderableWidget(it)
        }
        channel = EditBox(font, x, 228, 620, 20, Component.literal("Discord Channel ID")).also {
            it.setMaxLength(32)
            it.setValue(config.channelId)
            addRenderableWidget(it)
        }
        addRenderableWidget(Button.builder(Component.literal("Save & Connect")) { saveAndConnect() }.bounds(x, 284, 260, 20).build())
        addRenderableWidget(Button.builder(Component.literal("Close")) { minecraft.setScreen(null) }.bounds(x + 280, 284, 260, 20).build())
    }

    override fun render(context: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        context.fill(0, 0, width, height, 0xE0101010.toInt())
        val x = (width / 2 - 310).coerceAtLeast(32)
        context.drawString(font, "Aether Remote Control", x, 36, 0xFFFFFF, true)
        context.drawString(font, "Discord Bot Token", x, 98, 0xFFFFFF, true)
        context.drawString(font, "Discord Server ID", x, 156, 0xFFFFFF, true)
        context.drawString(font, "Discord Channel ID for ${Minecraft.getInstance().user.name}", x, 214, 0xFFFFFF, true)
        context.drawString(font, "Commands: !aether start|stop|status|connect|disconnect|panic|chat <text>|warp <place>", x, 262, 0xD8D8D8, true)
        super.render(context, mouseX, mouseY, delta)
    }

    private fun saveAndConnect() {
        config.discordBotToken = token.value.trim()
        config.discordGuildId = guild.value.trim()
        config.channelId = channel.value.trim()
        config.save()
        MinecraftBridge.feedback("§a[Remote Control] Discord config saved. Connecting bot...")
        AetherRemoteControlMod.restartFromConfig()
        minecraft.setScreen(null)
    }

    companion object {
        fun open() = Minecraft.getInstance().execute { Minecraft.getInstance().setScreen(ConfigScreen(Config.load())) }
        fun openIfMissing() {
            CompletableFuture.delayedExecutor(4, TimeUnit.SECONDS).execute {
                Minecraft.getInstance().execute {
                    val config = Config.load()
                    if (Minecraft.getInstance().screen == null && !config.configured) {
                        Minecraft.getInstance().setScreen(ConfigScreen(config))
                    }
                }
            }
        }
    }
}

private object FailsafeMonitor {
    private var nextAlertAt = 0L

    fun register() {
        ClientReceiveMessageEvents.GAME.register { message, _ -> inspect(message) }
        ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ -> inspect(message) }
    }

    private fun inspect(message: Component) {
        val text = message.string
        if (!text.contains("failsafe triggered", true) && !text.contains("farming exp text disappeared", true)) return
        val now = System.currentTimeMillis()
        if (now < nextAlertAt) return
        nextAlertAt = now + 60_000
        AetherRemoteControlMod.failsafe(text)
    }
}

private class DiscordBot(private val config: Config) : WebSocket.Listener {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    private val scheduler = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "Aether Discord Bot").apply { isDaemon = true }
    }
    private val buffer = StringBuilder()
    private val reconnecting = AtomicBoolean(false)
    private var socket: WebSocket? = null
    private var heartbeat: ScheduledFuture<*>? = null
    private var sequence = -1
    private var stopping = false

    val configured: Boolean get() = config.configured

    fun start() = scheduler.execute { connect() }

    fun stop() {
        stopping = true
        heartbeat?.cancel(false)
        socket?.sendClose(WebSocket.NORMAL_CLOSURE, "Stopping")
        scheduler.shutdownNow()
    }

    fun sendFailsafe(trigger: String) {
        val channel = config.channelId
        if (channel.isBlank()) {
            MinecraftBridge.feedback("§c[Remote Control] Failsafe detected, but no Discord channel is configured.")
            return
        }
        scheduler.execute {
            postScreenshot(channel, "@everyone", "Failsafe triggered: ${trigger.take(180)}")
        }
    }

    override fun onOpen(webSocket: WebSocket) {
        socket = webSocket
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
        try {
            buffer.append(data)
            if (last) {
                handleGateway(buffer.toString())
                buffer.setLength(0)
            }
        } finally {
            webSocket.request(1)
        }
        return CompletableFuture.completedFuture(null)
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
        heartbeat?.cancel(false)
        if (!stopping) reconnect("gateway closed")
        return CompletableFuture.completedFuture(null)
    }

    override fun onError(webSocket: WebSocket?, error: Throwable) {
        AetherRemoteControlMod.LOGGER.warn("Discord gateway error", error)
        heartbeat?.cancel(false)
        if (!stopping) reconnect("gateway error")
    }

    private fun connect() {
        http.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .buildAsync(URI.create("wss://gateway.discord.gg/?v=10&encoding=json"), this)
            .exceptionally {
                AetherRemoteControlMod.LOGGER.warn("Discord connection failed", it)
                reconnect("connect failed")
                null
            }
    }

    private fun handleGateway(payload: String) {
        val root = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull() ?: return
        if (root["s"]?.isJsonNull == false) sequence = root["s"].asInt
        when (root["op"]?.asInt) {
            10 -> {
                identify()
                val interval = root["d"].asJsonObject["heartbeat_interval"].asLong
                heartbeat = scheduler.scheduleAtFixedRate({ send("""{"op":1,"d":${if (sequence < 0) "null" else sequence}}""") }, interval, interval, TimeUnit.MILLISECONDS)
            }
            0 -> if (root["t"]?.asString == "MESSAGE_CREATE") handleMessage(root["d"].asJsonObject)
            1 -> send("""{"op":1,"d":${if (sequence < 0) "null" else sequence}}""")
            7, 9 -> reconnect("Discord requested reconnect")
        }
    }

    private fun identify() {
        val body = JsonObject().apply {
            addProperty("op", 2)
            add("d", JsonObject().apply {
                addProperty("token", config.discordBotToken)
                addProperty("intents", 1 or 512 or 32768)
                add("properties", JsonObject().apply {
                    addProperty("os", "linux")
                    addProperty("browser", AetherRemoteControlMod.MOD_ID)
                    addProperty("device", AetherRemoteControlMod.MOD_ID)
                })
            })
        }
        send(body.toString())
    }

    private fun handleMessage(message: JsonObject) {
        if (message["guild_id"]?.asString != config.discordGuildId) return
        if (message["author"]?.asJsonObject?.get("bot")?.asBoolean == true) return
        if (message["channel_id"]?.asString != config.channelId) return
        val content = message["content"]?.asString.orEmpty().trim()
        if (content.equals("!status", true)) {
            scheduler.execute { runStatus() }
            return
        }
        if (!content.startsWith("!aether", true)) return
        val args = content.drop("!aether".length).trim()
        scheduler.execute { runCommand(args.ifBlank { "help" }) }
    }

    private fun runCommand(args: String) {
        val command = args.substringBefore(' ').lowercase(Locale.ROOT)
        val rest = args.substringAfter(' ', "")
        val reply = when (command) {
            "start" -> MinecraftBridge.command("aether farming", "§a[Remote Control] Started Aether")
            "stop" -> MinecraftBridge.command("aether stop", "§a[Remote Control] Stopped Aether")
            "status" -> return runStatus()
            "connect" -> MinecraftBridge.connect("hypixel.net")
            "disconnect" -> MinecraftBridge.disconnect()
            "panic" -> MinecraftBridge.panic()
            "chat" -> if (rest.isBlank()) "Usage: `!aether chat <message>`" else MinecraftBridge.chat(rest)
            "warp" -> if (rest.isBlank()) "Usage: `!aether warp <place>`" else MinecraftBridge.chat("/warp $rest")
            else -> "Commands: `!aether start`, `stop`, `status`, `connect`, `disconnect`, `panic`, `chat <text>`, `warp <place>`"
        }
        postText(reply)
    }

    private fun runStatus() {
        MinecraftBridge.command("aether status", "§a[Remote Control] Status requested")
        Thread.sleep(750)
        postScreenshot(config.channelId, "", "Status for `${Minecraft.getInstance().user.name}`")
    }

    private fun postText(text: String) {
        val body = JsonObject().apply { addProperty("content", text) }.toString()
        request("/channels/${config.channelId}/messages").POST(HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json").sendAsync()
    }

    private fun postScreenshot(channel: String, content: String, title: String) {
        val image = runCatching { MinecraftBridge.screenshot() }.getOrElse {
            postText("$title\nCould not capture screenshot.")
            return
        }
        val boundary = "Aether${System.currentTimeMillis()}"
        val payload = JsonObject().apply {
            addProperty("content", listOf(content, title).filter { it.isNotBlank() }.joinToString("\n"))
        }
        request("/channels/$channel/messages")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(multipart(boundary, payload.toString(), image))
            .sendAsync()
    }

    private fun reconnect(reason: String) {
        if (!reconnecting.compareAndSet(false, true)) return
        AetherRemoteControlMod.LOGGER.info("Reconnecting Discord bot: {}", reason)
        scheduler.schedule({
            reconnecting.set(false)
            connect()
        }, 5, TimeUnit.SECONDS)
    }

    private fun send(text: String) {
        socket?.sendText(text, true)
    }

    private fun request(path: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create("https://discord.com/api/v10$path"))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bot ${config.discordBotToken}")

    private fun HttpRequest.Builder.sendAsync() {
        http.sendAsync(build(), HttpResponse.BodyHandlers.ofString()).thenAccept {
            if (it.statusCode() !in 200..299) {
                AetherRemoteControlMod.LOGGER.warn("Discord request failed: HTTP {} {}", it.statusCode(), it.body())
            }
        }
    }

    private fun multipart(boundary: String, payload: String, image: ByteArray): HttpRequest.BodyPublisher {
        val newline = "\r\n"
        val start = buildString {
            append("--$boundary$newline")
            append("""Content-Disposition: form-data; name="payload_json"""")
            append("$newline$newline$payload$newline")
            append("--$boundary$newline")
            append("""Content-Disposition: form-data; name="files[0]"; filename="status.png"""")
            append(newline)
            append("Content-Type: image/png$newline$newline")
        }.toByteArray(StandardCharsets.UTF_8)
        val end = "$newline--$boundary--$newline".toByteArray(StandardCharsets.UTF_8)
        return HttpRequest.BodyPublishers.concat(
            HttpRequest.BodyPublishers.ofByteArray(start),
            HttpRequest.BodyPublishers.ofByteArray(image),
            HttpRequest.BodyPublishers.ofByteArray(end)
        )
    }
}

private object MinecraftBridge {
    fun command(command: String, feedback: String): String {
        val clean = command.removePrefix("/")
        input(clean, feedback, true)
        return "`/$clean` sent."
    }

    fun chat(message: String): String {
        input(message.removePrefix("/"), "§a[Remote Control] Sent $message", message.startsWith("/"))
        return "`$message` sent."
    }

    fun feedback(message: String) = Minecraft.getInstance().execute {
        Minecraft.getInstance().player?.displayClientMessage(Component.literal(message), false)
    }

    fun connect(address: String): String {
        val client = Minecraft.getInstance()
        client.execute {
            val server = ServerData(address, address, ServerData.Type.OTHER)
            ConnectScreen.startConnecting(TitleScreen(), client, ServerAddress.parseString(address), server, false, null)
        }
        return "Connecting to `$address`."
    }

    fun disconnect(): String {
        val client = Minecraft.getInstance()
        client.execute { client.disconnect(TitleScreen(), false) }
        return "Disconnect requested."
    }

    fun panic(): String {
        CompletableFuture.delayedExecutor(250, TimeUnit.MILLISECONDS).execute {
            Runtime.getRuntime().halt(1)
        }
        return "Panic crash requested."
    }

    @Throws(IOException::class)
    fun screenshot(): ByteArray {
        val future = CompletableFuture<ByteArray>()
        val client = Minecraft.getInstance()
        client.execute {
            Screenshot.takeScreenshot(client.mainRenderTarget) { image: NativeImage ->
                var temp: Path? = null
                try {
                    image.use {
                        temp = Files.createTempFile("aether-status-", ".png")
                        it.writeToFile(temp)
                        future.complete(Files.readAllBytes(temp))
                    }
                } catch (e: IOException) {
                    future.completeExceptionally(e)
                } finally {
                    temp?.let { Files.deleteIfExists(it) }
                }
            }
        }
        return try {
            future.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            throw IOException("Timed out while capturing screenshot", e)
        }
    }

    private fun input(text: String, feedback: String, command: Boolean) {
        Minecraft.getInstance().execute {
            runCatching {
                if (!ready()) return@execute
                val client = Minecraft.getInstance()
                val connection = client.connection ?: return@execute
                if (command) connection.sendCommand(text) else connection.sendChat(text)
                client.player?.displayClientMessage(Component.literal(feedback), false)
            }.onFailure {
                AetherRemoteControlMod.LOGGER.warn("Remote Minecraft input failed", it)
            }
        }
    }

    private fun ready() = Minecraft.getInstance().player != null && Minecraft.getInstance().connection != null && Minecraft.getInstance().level != null
}
