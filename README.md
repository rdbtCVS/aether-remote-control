# Aether Remote Control

Control your Aether farming in Minecraft from a Discord bot — type a command in Discord, farming starts/stops in-game.

---

## What You Need

- A PC running Windows/Mac/Linux
- Minecraft with [Fabric](https://fabricmc.net/use/installer/) installed
- A Discord account

---

## Step 1 — Create Your Discord Bot

1. Go to [discord.com/developers/applications](https://discord.com/developers/applications)
2. Click **New Application** → give it a name → click **Create**
3. On the left sidebar click **Bot**
4. Click **Reset Token** → copy the token and save it somewhere (this is your `DISCORD BOT TOKEN`)
5. Scroll down and turn on **Server Members Intent** and **Message Content Intent**
6. On the left sidebar click **OAuth2**
7. Under **Scopes** tick `bot` and `applications.commands`
8. Under **Bot Permissions** tick `Send Messages`
9. Copy the URL at the bottom and open it in your browser to invite the bot to your server
10. Go to **General Information** on the left sidebar → copy the `Application ID` and save it somewhere (this is your `APPLICATION ID`)

---

## Step 2 — Get Your Server ID

1. In Discord, go to **Settings → Advanced** and turn on **Developer Mode**
2. Right-click your server name → **Copy Server ID** — save it (this is your `GUILD ID`)

---

## Step 3 — Install the Minecraft Mod

1. Download `aether-remote-control-1.0.0.jar` from releases
2. Drop it into your Minecraft `mods` folder
    - Windows: `%appdata%\.minecraft\mods`
    - Mac: `~/Library/Application Support/minecraft/mods`
3. Make sure you have Fabric and Fabric API installed — [guide here](https://fabricmc.net/use/installer/)

---

## Step 4 — Configure the Bot
1. Do /aetherremotecontrol and put in the following information:
    - `DISCORD BOT TOKEN` — from Step 1
    - `APPLICATION ID` — from Step 1
    - `GUILD ID` — from Step 2
2. Click **Save** and your bot should now be ready!

In any channel your bot has access to:

- `/aether start` — starts Aether farming
- `/aether stop` — stops Aether farming
- `/aether status` — sends a Discord webhook update (must have the webhook option configured in Aether)
- `/aether chat` — sends a message to Minecraft chat
---
