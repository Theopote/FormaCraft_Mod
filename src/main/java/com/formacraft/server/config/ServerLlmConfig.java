package com.formacraft.server.config;

import com.formacraft.common.model.request.FormaRequest;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Dedicated-server authority. Never combine an admin key with a client-controlled URL. */
public final class ServerLlmConfig {
    private static final Properties CONFIG = load();
    private ServerLlmConfig() {}

    private static Properties load() {
        Properties config = new Properties();
        Path path = Path.of("config/formacraft_server.properties");
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                config.load(reader);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read server LLM configuration", e);
            }
        }
        return config;
    }

    private static String value(String env, String property) {
        String value = System.getenv(env);
        if (value == null || value.isBlank()) value = CONFIG.getProperty(property);
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static void apply(FormaRequest request, boolean dedicated) {
        if (request == null) return;
        // Backend owns prompts; never trust client-supplied system/user prompt blobs.
        request.setSystemPrompt(null);
        request.setUserPrompt(null);
        if (!dedicated) return;
        request.setApiKey(value("FORMACRAFT_API_KEY", "apiKey"));
        request.setLlmBaseUrl(value("FORMACRAFT_LLM_BASE_URL", "llmBaseUrl"));
        request.setLlmProvider(value("FORMACRAFT_LLM_PROVIDER", "llmProvider"));
        request.setModel(value("FORMACRAFT_LLM_MODEL", "model"));
    }

    /**
     * Dedicated proxy {@code /summarize}: drop client {@code apiKey}/{@code llmBaseUrl}
     * (and provider/model) then inject server credentials. Integrated servers pass through.
     */
    public static String rewriteProxySummarizeJson(String raw, boolean dedicated) {
        if (!dedicated) {
            return raw == null || raw.isBlank() ? "{\"transcript\":\"\"}" : raw;
        }
        JsonObject obj;
        try {
            if (raw == null || raw.isBlank()) {
                obj = new JsonObject();
            } else {
                obj = JsonParser.parseString(raw).getAsJsonObject();
            }
        } catch (Exception e) {
            obj = new JsonObject();
            if (raw != null && !raw.isBlank()) {
                obj.addProperty("transcript", raw);
            }
        }
        obj.remove("apiKey");
        obj.remove("llmBaseUrl");
        obj.remove("llmProvider");
        obj.remove("model");
        putIfPresent(obj, "apiKey", value("FORMACRAFT_API_KEY", "apiKey"));
        putIfPresent(obj, "llmBaseUrl", value("FORMACRAFT_LLM_BASE_URL", "llmBaseUrl"));
        putIfPresent(obj, "llmProvider", value("FORMACRAFT_LLM_PROVIDER", "llmProvider"));
        putIfPresent(obj, "model", value("FORMACRAFT_LLM_MODEL", "model"));
        if (!obj.has("transcript") || obj.get("transcript").isJsonNull()) {
            obj.addProperty("transcript", "");
        }
        return obj.toString();
    }

    private static void putIfPresent(JsonObject obj, String key, String value) {
        if (value != null && !value.isBlank()) {
            obj.addProperty(key, value);
        }
    }

    /** Shared secret for Python orchestrator {@code X-FormaCraft-Secret} header. */
    public static String orchestratorSecret() {
        return value("FORMACRAFT_ORCHESTRATOR_SECRET", "orchestratorSecret");
    }

    /** OP level required for chat {@code RequestBuildPayload}. Dedicated default 2; integrated 0. */
    public static int requireBuildPermissionLevel(boolean dedicated) {
        String raw = value("FORMACRAFT_REQUIRE_BUILD_PERMISSION_LEVEL", "requireBuildPermissionLevel");
        if (raw != null) {
            try {
                return Math.max(0, Integer.parseInt(raw.trim()));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return dedicated ? 2 : 0;
    }

    public static int buildMaxConcurrentPerPlayer() {
        return intOr("FORMACRAFT_BUILD_MAX_CONCURRENT_PER_PLAYER", "buildMaxConcurrentPerPlayer", 1);
    }

    public static int buildRateLimitPerWindow() {
        return intOr("FORMACRAFT_BUILD_RATE_LIMIT_PER_WINDOW", "buildRateLimitPerWindow", 6);
    }

    public static int buildRateLimitWindowSeconds() {
        return intOr("FORMACRAFT_BUILD_RATE_LIMIT_WINDOW_SECONDS", "buildRateLimitWindowSeconds", 60);
    }

    private static int intOr(String env, String property, int defaultValue) {
        String raw = value(env, property);
        if (raw == null) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
