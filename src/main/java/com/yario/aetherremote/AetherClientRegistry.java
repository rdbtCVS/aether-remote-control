package com.yario.aetherremote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class AetherClientRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(AetherRemoteControlMod.MOD_ID);
    private static final Path REGISTRY_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("aether-remote-clients.json");
    private static final long STALE_AFTER_MILLIS = 5L * 60L * 1000L;

    private AetherClientRegistry() {
    }

    static String instanceIdFromAccountName(String accountName) {
        String trimmedName = accountName == null ? "" : accountName.trim();
        if (trimmedName.isBlank()) {
            return "unknown";
        }

        return trimmedName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    static synchronized void register(AetherClientInstance instance) {
        List<AetherClientInstance> instances = readAllIncludingStale();
        instances.removeIf(existing -> existing.id().equals(instance.id()) || existing.port() == instance.port());
        instances.add(instance);
        write(instances);
    }

    static synchronized void unregister(String instanceId) {
        List<AetherClientInstance> instances = readAllIncludingStale();
        instances.removeIf(instance -> instance.id().equals(instanceId));
        write(instances);
    }

    static synchronized List<AetherClientInstance> readActive() {
        long cutoff = Instant.now().toEpochMilli() - STALE_AFTER_MILLIS;
        List<AetherClientInstance> instances = readAllIncludingStale().stream()
                .filter(instance -> instance.updatedAt() >= cutoff)
                .sorted(Comparator.comparing(AetherClientInstance::accountName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        write(instances);
        return instances;
    }

    static synchronized Optional<AetherClientInstance> findActive(String instanceId) {
        return readActive().stream()
                .filter(instance -> instance.id().equals(instanceId))
                .findFirst();
    }

    private static List<AetherClientInstance> readAllIncludingStale() {
        if (!Files.exists(REGISTRY_PATH)) {
            return new ArrayList<>();
        }

        try {
            JsonElement root = JsonParser.parseString(Files.readString(REGISTRY_PATH, StandardCharsets.UTF_8));
            if (!root.isJsonArray()) {
                return new ArrayList<>();
            }

            List<AetherClientInstance> instances = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }

                JsonObject object = element.getAsJsonObject();
                String id = readString(object, "id");
                String accountName = readString(object, "accountName");
                int port = readInt(object, "port");
                long updatedAt = readLong(object, "updatedAt");
                if (!id.isBlank() && !accountName.isBlank() && port > 0) {
                    instances.add(new AetherClientInstance(id, accountName, port, updatedAt));
                }
            }

            return instances;
        } catch (IOException | JsonSyntaxException exception) {
            LOGGER.warn("Failed to read Aether client registry", exception);
            return new ArrayList<>();
        }
    }

    private static void write(List<AetherClientInstance> instances) {
        JsonArray root = new JsonArray();
        for (AetherClientInstance instance : instances) {
            JsonObject object = new JsonObject();
            object.addProperty("id", instance.id());
            object.addProperty("accountName", instance.accountName());
            object.addProperty("port", instance.port());
            object.addProperty("updatedAt", instance.updatedAt());
            root.add(object);
        }

        try {
            Files.createDirectories(REGISTRY_PATH.getParent());
            Files.writeString(REGISTRY_PATH, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            LOGGER.warn("Failed to write Aether client registry", exception);
        }
    }

    private static String readString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static int readInt(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? 0 : element.getAsInt();
    }

    private static long readLong(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? 0L : element.getAsLong();
    }
}
