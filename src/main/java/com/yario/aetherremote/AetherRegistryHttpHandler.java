package com.yario.aetherremote;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

final class AetherRegistryHttpHandler implements HttpHandler {
    private static final String AUTH_HEADER = "X-Remote-Auth";

    private final String expectedToken;
    private final BiConsumer<String, String> failsafeAlertHandler;

    AetherRegistryHttpHandler(String expectedToken, BiConsumer<String, String> failsafeAlertHandler) {
        this.expectedToken = expectedToken;
        this.failsafeAlertHandler = failsafeAlertHandler;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String authHeader = exchange.getRequestHeaders().getFirst(AUTH_HEADER);
            if (authHeader == null || !expectedToken.equals(authHeader)) {
                sendResponse(exchange, 401, "Unauthorized");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            if ("/aether-registry/register".equals(path)) {
                register(exchange);
                return;
            }

            if ("/aether-registry/unregister".equals(path)) {
                unregister(exchange);
                return;
            }

            if ("/aether-registry/failsafe".equals(path)) {
                failsafe(exchange);
                return;
            }

            sendResponse(exchange, 404, "Not Found");
        } finally {
            exchange.close();
        }
    }

    private void register(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
        String id = params.getOrDefault("id", "");
        String accountName = params.getOrDefault("accountName", "");
        int port = parsePort(params.get("port"));
        if (id.isBlank() || accountName.isBlank() || port <= 0) {
            sendResponse(exchange, 400, "Missing id, accountName, or port");
            return;
        }

        AetherClientRegistry.register(new AetherClientInstance(id, accountName, port, Instant.now().toEpochMilli()));
        sendResponse(exchange, 200, "Registered " + id);
    }

    private void unregister(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
        String id = params.getOrDefault("id", "");
        if (id.isBlank()) {
            sendResponse(exchange, 400, "Missing id");
            return;
        }

        AetherClientRegistry.unregister(id);
        sendResponse(exchange, 200, "Unregistered " + id);
    }

    private void failsafe(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
        String id = params.getOrDefault("id", "");
        String message = params.getOrDefault("message", "");
        if (id.isBlank()) {
            sendResponse(exchange, 400, "Missing id");
            return;
        }

        failsafeAlertHandler.accept(id, message);
        sendResponse(exchange, 200, "Failsafe alert accepted");
    }

    private int parsePort(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
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

            params.put(
                    decode(pair.substring(0, separatorIndex)),
                    decode(pair.substring(separatorIndex + 1))
            );
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
