package com.formacraft.ai;

import com.formacraft.config.SettingsConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 对话历史总结服务：调用 python_backend 的 /summarize
 */
public class ConversationSummaryService {

    public static class Summary {
        public final String title;
        public final String summary;

        public Summary(String title, String summary) {
            this.title = title == null ? "新对话" : title;
            this.summary = summary == null ? "" : summary;
        }
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final Gson gson = new Gson();

    public Summary summarize(String transcript) {
        String text = transcript == null ? "" : transcript.trim();
        if (text.isEmpty()) return new Summary("新对话", "");

        try {
            String url = resolveSummarizeUrl();
            String body = buildRequestJson(text);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return parseSummary(resp.body());
            }
        } catch (IOException | InterruptedException ignored) {
        }

        // fallback
        String first = text.split("\n", -1)[0].trim();
        String title = first.isEmpty() ? "新对话" : (first.length() <= 28 ? first : first.substring(0, 27) + "…");
        String summary = text.length() <= 180 ? text : text.substring(0, 179) + "…";
        return new Summary(title, summary);
    }

    private String buildRequestJson(String transcript) {
        JsonObject root = new JsonObject();
        root.addProperty("transcript", transcript);

        SettingsConfig cfg = SettingsConfig.INSTANCE;
        if (cfg != null) {
            if (cfg.apiKey != null && !cfg.apiKey.isEmpty()) root.addProperty("apiKey", cfg.apiKey);
            if (cfg.model != null && !cfg.model.isEmpty()) root.addProperty("model", cfg.model);
            root.addProperty("temperature", cfg.temperature);
        }
        return gson.toJson(root);
    }

    private Summary parseSummary(String json) {
        if (json == null || json.isEmpty()) return new Summary("新对话", "");
        try {
            JsonObject obj = gson.fromJson(json, JsonObject.class);
            if (obj == null) return new Summary("新对话", "");
            String title = obj.has("title") ? obj.get("title").getAsString() : "新对话";
            String summary = obj.has("summary") ? obj.get("summary").getAsString() : "";
            return new Summary(title, summary);
        } catch (Exception e) {
            return new Summary("新对话", "");
        }
    }

    private String resolveSummarizeUrl() {
        String base = SettingsConfig.INSTANCE != null ? SettingsConfig.INSTANCE.orchestratorEndpoint : null;
        if (base == null || base.isBlank()) base = "http://localhost:8000";
        base = base.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.endsWith("/summarize")) return base;
        return base + "/summarize";
    }
}

