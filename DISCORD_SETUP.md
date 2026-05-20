# Discord integration

The Discord bot is now built into the Minecraft mod jar. You do not need a separate Node.js program.

## What to create in Discord

1. Open the Discord Developer Portal.
2. Create an application.
3. Add a bot.
4. Copy the bot token.
5. Copy the application ID.
6. Invite the bot to your server with these scopes:

```text
bot
applications.commands
```

The bot does not need Message Content Intent.

## Configure in Minecraft

1. Put the mod jar in your Fabric mods folder.
2. Start Minecraft.
3. The Aether Remote Control screen opens automatically if Discord settings are missing.
4. Paste:

```text
Discord Bot Token
Application ID
Server ID
```

5. Click `Save & Connect`.

The mod registers these Discord slash commands:

```text
/aether-start
/aether-stop
```

## Reopen the config screen

From the same PC as Minecraft, run:

```powershell
curl.exe "http://127.0.0.1:8080/aether-control?command=config" -H "X-Remote-Auth: MY_SECURE_TOKEN_123"
```

## Local HTTP tests

```powershell
curl.exe "http://127.0.0.1:8080/aether-control?command=start" -H "X-Remote-Auth: MY_SECURE_TOKEN_123"
curl.exe "http://127.0.0.1:8080/aether-control?command=stop" -H "X-Remote-Auth: MY_SECURE_TOKEN_123"
```

The Minecraft mod itself binds to `127.0.0.1`, so it only accepts requests from the same machine by default.
