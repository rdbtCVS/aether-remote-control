# Aether Remote Control

Discord message control for local Minecraft clients running Aether.

## Requirements

- Minecraft 1.21.11 with Fabric
- Fabric API
- Fabric Language Kotlin
- A Discord bot token, server ID, and one Discord channel per Minecraft instance

## Setup

1. Put the jar in your Minecraft `mods` folder.
2. Launch the game.
3. Press **F10** or run `/aetherremotecontrol`.
4. Enter the Discord bot token, server ID, and the channel ID for that Minecraft instance.
5. Save.

The bot needs Message Content Intent enabled in the Discord Developer Portal.

For multiple accounts, use a separate `.minecraft` instance/config directory for each account, then save a different channel ID in each client's config screen.

## Commands

Use these in the instance's configured Discord channel:

| Command                       | Action                                              |
| ----------------------------- | --------------------------------------------------- |
| `!aether start`               | Sends `/aether farming`                             |
| `!aether stop`                | Sends `/aether stop`                                |
| `!aether status` or `!status` | Sends `/aether status` and posts a screenshot       |
| `!aether connect`             | Connects to `hypixel.net`                           |
| `!aether disconnect`          | Disconnects from the server                         |
| `!aether panic`               | Crashes the client process                          |
| `!aether chat <text>`         | Sends chat text, or a command if it starts with `/` |
| `!aether warp <place>`        | Sends `/warp <place>`                               |

Failsafe alerts post to that instance's configured Discord channel.

## Build

```sh
bash gradlew build
```
