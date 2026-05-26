# Aether Remote Control

Control your Aether farming in Minecraft straight from Discord. Type a command (or tap a button) in your Discord server and it happens in-game — start, stop, check status with a live screenshot, send chat, warp, reconnect, and more. It can even ping you automatically when a failsafe trips while you're away.

---

## What you need

- A PC running the game (Windows, macOS, or Linux)
- Minecraft **1.21.11** with [Fabric](https://fabricmc.net/use/installer/) installed
- [Fabric API](https://modrinth.com/mod/fabric-api) for 1.21.11
- Java **21** or newer
- A Discord account and a server you can add a bot to

---

## Install (5 minutes)

1. Make sure Fabric and Fabric API are installed for Minecraft 1.21.11.
2. Download `aether-remote-control-1.0.0.jar` from the releases page.
3. Drop the jar into your Minecraft `mods` folder:
   - **Windows:** `%appdata%\.minecraft\mods`
   - **macOS:** `~/Library/Application Support/minecraft/mods`
   - **Linux:** `~/.minecraft/mods`
4. Launch Minecraft using the Fabric profile.

The first time it runs, the settings screen opens automatically so you can connect Discord. You can reopen it any time with **F10** or by typing `/aetherremotecontrol` in chat.

---

## Connect Discord (one-time setup)

You need three things from Discord: a **bot token**, an **application ID**, and your **server ID**.

### 1. Create the bot

1. Go to [discord.com/developers/applications](https://discord.com/developers/applications).
2. Click **New Application**, give it a name, and create it.
3. Open the **Bot** tab → **Reset Token** → copy the token somewhere safe. *(This is your bot token.)*
4. Still on the **Bot** tab, turn on **Message Content Intent**.
5. Open the **OAuth2** tab. Under **Scopes**, tick `bot` and `applications.commands`. Under **Bot Permissions**, tick `Send Messages`.
6. Copy the generated URL at the bottom, open it in your browser, and invite the bot to your server.
7. Open the **General Information** tab and copy the **Application ID**.

### 2. Get your server ID

1. In Discord, go to **Settings → Advanced** and turn on **Developer Mode**.
2. Right-click your server's icon → **Copy Server ID**.

### 3. Enter them in Minecraft

1. Press **F10** (or type `/aetherremotecontrol`) to open the settings screen.
2. Paste in your **Bot Token**, **Application ID**, and **Server ID**.
3. Click **Save & Connect**.

That's it — the bot connects and its commands appear in your server within a few seconds. If the slash commands don't show up right away, restart Minecraft once.

---

## Using it from Discord

There are two ways to control your game: **quick chat commands** and the **slash command**. Both do the same things — use whichever you like.

### Quick chat commands

Type these as normal messages in any channel the bot can see:

| Command | What it does |
| --- | --- |
| `!control` | Opens the control panel (pick a player, then tap buttons) |
| `!status` | Sends a live screenshot of what's on screen right now |

### The control panel

`!control` (or `/aether task:panel`) posts an interactive panel. Pick the account you want from the dropdown, then use the buttons:

| Button | What it does |
| --- | --- |
| **Start** | Starts Aether farming |
| **Stop** | Stops farming |
| **Status** | Takes a fresh screenshot of the game |
| **Warps** | Opens a menu to teleport (hub, garden, island, and more) |
| **Chat** | Pops up a box to type a chat message or command to send in-game |
| **Connect** | Reconnects to Hypixel |
| **Disconnect** | Leaves the current server |
| **Panic** | Emergency bail-out — instantly closes the game |
| **Back** | Go back, or switch to a different player |

### The `/aether` slash command

If you'd rather type one command, use `/aether`. It has three parts:

- **task** *(required)* — what to do: `panel`, `start`, `stop`, `status`, `connect`, `disconnect`, `panic`, `chat`, or a warp.
- **user** *(optional)* — which account to control. Start typing and Discord will autocomplete the players that are online. You only need this when more than one account is running.
- **message** *(optional)* — the text to send when the task is `chat`.

Examples:

```text
/aether task:start
/aether task:start user:YourAccount
/aether task:status user:YourAccount
/aether task:chat user:YourAccount message:gg everyone
/aether task:/warp garden user:YourAccount
/aether task:panel
```

---

## Automatic failsafe alerts

If Aether trips a failsafe while you're away (for example, a staff check), the bot automatically posts a screenshot and pings **@everyone** so you can react fast.

Alerts go to the channel where the bot was last used, so **run `!control` once** in the channel you want alerts in after starting up.

---

## Controlling more than one account

Running several Minecraft accounts on the same PC? They all show up automatically. Each appears as its own player in the control panel's dropdown and in the `/aether user:` autocomplete, so you can manage your whole farm from one Discord server.

---

## Troubleshooting

- **Slash commands don't appear.** Restart Minecraft after saving your Discord settings, and make sure the bot is actually in your server.
- **Bot replies but nothing happens in-game.** Make sure you're loaded into a world or connected to a server — commands can't run on the menu screen.
- **`!control` does nothing.** Double-check that **Message Content Intent** is turned on for the bot (see setup step 1.4).
- **No failsafe alerts.** Run `!control` once in the channel you want alerts in so the bot knows where to send them.

---

## Building from source

For developers who want to build the mod themselves. Use the included Gradle wrapper:

```powershell
# Windows
.\gradlew.bat build
```

```sh
# macOS / Linux
./gradlew build
```

The finished jar lands in `build/libs/`. To also copy release artifacts into `releases/`:

```powershell
.\gradlew.bat clean build prepareReleaseArtifacts
```

You can override the version with `-Prelease_version=1.0.1`.
