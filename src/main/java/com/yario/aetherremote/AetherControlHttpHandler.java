package com.yario.aetherremote;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class AetherControlHttpHandler implements HttpHandler {
    private static final String AUTH_HEADER = "X-Remote-Auth";
    private static final String START_COMMAND = "/aether start";
    private static final String STOP_COMMAND = "/aether stop";
    private static final String STATUS_COMMAND = "/aether status";

    private final String expectedToken;

    public AetherControlHttpHandler(String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            String authHeader = exchange.getRequestHeaders().getFirst(AUTH_HEADER);
            if (authHeader == null || !expectedToken.equals(authHeader)) {
                sendResponse(exchange, 401, "Unauthorized");
                return;
            }

            Map<String, String> queryParams = parseQuery(exchange.getRequestURI().getRawQuery());
            String requestedCommand = queryParams.get("command");

            if (requestedCommand == null || requestedCommand.isBlank()) {
                sendResponse(exchange, 400, "Missing command. Use command=start, command=stop, or command=status");
                return;
            }

            switch (requestedCommand.toLowerCase(Locale.ROOT)) {
                case "start" -> {
                    executeSlashCommand(START_COMMAND, "\u00a7a[Remote Control] Executed start command");
                    sendResponse(exchange, 200, "Triggered " + START_COMMAND);
                }
                case "stop" -> {
                    executeSlashCommand(STOP_COMMAND, "\u00a7a[Remote Control] Executed stop command");
                    sendResponse(exchange, 200, "Triggered " + STOP_COMMAND);
                }
                case "status" -> {
                    executeSlashCommand(STATUS_COMMAND, "\u00a7a[Remote Control] Executed status command");
                    sendResponse(exchange, 200, "Triggered " + STATUS_COMMAND);
                }
                case "config" -> {
                    AetherConfigScreen.open();
                    sendResponse(exchange, 200, "Opened config screen");
                }
                default -> sendResponse(exchange, 400, "Invalid command. Use command=start, command=stop, command=status, or command=config");
            }
        } finally {
            exchange.close();
        }
    }

    private void executeSlashCommand(String slashCommand, String feedbackMessage) {
        MinecraftClientBridge.executeSlashCommand(stripLeadingSlash(slashCommand), feedbackMessage);
    }

    private String stripLeadingSlash(String slashCommand) {
        if (slashCommand.startsWith("/")) {
            return slashCommand.substring(1);
        }

        return slashCommand;
    }

    private Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new HashMap<>();

        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }

        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            int separatorIndex = pair.indexOf('=');
            if (separatorIndex <= 0) {
                continue;
            }

            String key = decode(pair.substring(0, separatorIndex));
            String value = decode(pair.substring(separatorIndex + 1));
            params.put(key, value);
        }

        return params;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] responseBody = message.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, responseBody.length);

        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(responseBody);
        }
    }
}
