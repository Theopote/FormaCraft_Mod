package com.formacraft.server.config;

import com.formacraft.common.model.request.FormaRequest;
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
        if (request == null || !dedicated) return;
        request.setApiKey(value("FORMACRAFT_API_KEY", "apiKey"));
        request.setLlmBaseUrl(value("FORMACRAFT_LLM_BASE_URL", "llmBaseUrl"));
        request.setLlmProvider(value("FORMACRAFT_LLM_PROVIDER", "llmProvider"));
        request.setModel(value("FORMACRAFT_LLM_MODEL", "model"));
    }
}
