package com.formacraft.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.formacraft.common.lang.StructureData;
import com.formacraft.config.SettingsConfig;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.util.math.BlockPos;

public class HttpAIService implements AIService {

    private final AIService fallback = new BasicAIService();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final Gson gson = new Gson();

    @Override
    public AIResult generateBuildingPlan(BuildingRequest request) {
        if (request == null) {
            return fallback.generateBuildingPlan(null);
        }

        try {
            String jsonBody = buildRequestJson(request);
            String url = resolveBuildUrl();
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String body = response.body();
                StructureData structureData = parseStructureDataFromBuildResponse(body);
                return new AIResult(body, structureData);
            }
        } catch (IOException | InterruptedException e) {
            // TODO: 可在此处加入日志记录
        }

        return fallback.generateBuildingPlan(request);
    }

    @Override
    public AIResult generateBuildingPlan(BuildingRequest request, AICancelToken token) {
        if (token != null && token.isCancelled()) return null;
        if (request == null) {
            return fallback.generateBuildingPlan(null);
        }

        try {
            if (token != null && token.isCancelled()) return null;

            String jsonBody = buildRequestJson(request);
            String url = resolveBuildUrl();
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (token != null && token.isCancelled()) return null;

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String body = response.body();
                if (token != null && token.isCancelled()) return null;
                StructureData structureData = parseStructureDataFromBuildResponse(body);
                return new AIResult(body, structureData);
            }
        } catch (InterruptedException e) {
            // Stop 按钮会通过 future.cancel(true) 触发线程中断，从而中断阻塞的 send()
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException e) {
            if (token != null && token.isCancelled()) return null;
        }

        if (token != null && token.isCancelled()) return null;
        return fallback.generateBuildingPlan(request);
    }

    private String buildRequestJson(BuildingRequest request) {
        // 适配 python_backend 的 /build（FormaRequestAdapter）
        String prompt = escapeJson(request.getPrompt());
        BlockPos origin = request.getOrigin();
        String dimension = escapeJson(request.getDimensionId());
        String sessionId = request.getSessionId() != null ? escapeJson(request.getSessionId()) : null;

        int x = origin != null ? origin.getX() : 0;
        int y = origin != null ? origin.getY() : 0;
        int z = origin != null ? origin.getZ() : 0;

        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"requestText\":\"").append(prompt).append("\",");
        sb.append("\"playerPos\":{\"x\":").append(x).append(",\"y\":").append(y).append(",\"z\":").append(z).append("},");
        sb.append("\"facing\":\"NORTH\",");
        sb.append("\"dimension\":\"").append(dimension).append("\"");

        if (sessionId != null && !sessionId.isEmpty()) {
            sb.append(',').append("\"sessionId\":\"").append(sessionId).append("\"");
        }

        // chat_history as JSON array of strings
        if (request.getChatHistory() != null && !request.getChatHistory().isEmpty()) {
            sb.append(',').append("\"chatHistory\": [");
            boolean first = true;
            for (String line : request.getChatHistory()) {
                if (!first) sb.append(',');
                String escaped = escapeJson(line == null ? "" : line);
                sb.append('"').append(escaped).append('"');
                first = false;
            }
            sb.append(']');
        }

        // settings: apiKey/model/temperature
        SettingsConfig cfg = SettingsConfig.INSTANCE;
        if (cfg != null) {
            if (cfg.apiKey != null && !cfg.apiKey.isEmpty()) {
                sb.append(',').append("\"apiKey\":\"").append(escapeJson(cfg.apiKey)).append("\"");
            }
            if (cfg.model != null && !cfg.model.isEmpty()) {
                sb.append(',').append("\"model\":\"").append(escapeJson(cfg.model)).append("\"");
            }
            sb.append(',').append("\"temperature\":").append(cfg.temperature);
        }

        sb.append('}');
        return sb.toString();
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private String resolveBuildUrl() {
        String base = SettingsConfig.INSTANCE != null ? SettingsConfig.INSTANCE.orchestratorEndpoint : null;
        if (base == null || base.isBlank()) base = "http://localhost:8000";
        base = base.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.endsWith("/build")) return base;
        return base + "/build";
    }

    private StructureData parseStructureDataFromBuildResponse(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JsonObject root = gson.fromJson(json, JsonObject.class);
            if (root == null) return null;

            // CompositeSpec: {"structures":[{"spec":{...}}]}
            if (root.has("structures") && root.get("structures").isJsonArray()) {
                JsonArray arr = root.getAsJsonArray("structures");
                if (!arr.isEmpty() && arr.get(0).isJsonObject()) {
                    JsonObject first = arr.get(0).getAsJsonObject();
                    if (first.has("spec") && first.get("spec").isJsonObject()) {
                        return structureDataFromBuildingSpec(first.getAsJsonObject("spec"));
                    }
                }
            }

            // BuildingSpec: {"type": "...", "materials": {...}, "footprint": {...}, "height": ...}
            if (root.has("type") && root.has("materials")) {
                return structureDataFromBuildingSpec(root);
            }

            return null;
        } catch (JsonParseException e) {
            return null;
        }
    }

    private StructureData structureDataFromBuildingSpec(JsonObject spec) {
        if (spec == null) return null;

        String type = spec.has("type") && spec.get("type").isJsonPrimitive()
                ? spec.get("type").getAsString() : "CUSTOM";
        String style = spec.has("style") && spec.get("style").isJsonPrimitive()
                ? spec.get("style").getAsString() : "DEFAULT";

        int height = spec.has("height") && spec.get("height").isJsonPrimitive()
                ? safeGetInt(spec, "height") : 0;

        String material = "minecraft:stone";
        if (spec.has("materials") && spec.get("materials").isJsonObject()) {
            JsonObject mats = spec.getAsJsonObject("materials");
            if (mats.has("wall") && mats.get("wall").isJsonPrimitive()) {
                material = mats.get("wall").getAsString();
            }
        }

        int width = 0;
        int depth = 0;
        if (spec.has("footprint") && spec.get("footprint").isJsonObject()) {
            JsonObject fp = spec.getAsJsonObject("footprint");
            String shape = fp.has("shape") && fp.get("shape").isJsonPrimitive()
                    ? fp.get("shape").getAsString() : "rectangle";
            if ("circle".equalsIgnoreCase(shape)) {
                int radius = fp.has("radius") && fp.get("radius").isJsonPrimitive()
                        ? safeGetInt(fp, "radius") : 0;
                width = radius * 2;
                depth = radius * 2;
            } else {
                width = fp.has("width") && fp.get("width").isJsonPrimitive()
                        ? safeGetInt(fp, "width") : 0;
                depth = fp.has("depth") && fp.get("depth").isJsonPrimitive()
                        ? safeGetInt(fp, "depth") : 0;
            }
        }

        // 旧 StructureData 的 towers 在此版本里没有明确对应字段，暂用 0
        return new StructureData(
                type != null ? type : "CUSTOM",
                material != null ? material : "minecraft:stone",
                0,
                style != null ? style : "DEFAULT",
                Math.max(0, width),
                Math.max(0, height),
                Math.max(0, depth)
        );
    }

    private int safeGetInt(JsonObject obj, String field) {
        if (obj == null || !obj.has(field) || !obj.get(field).isJsonPrimitive()) return 0;
        try {
            return obj.get(field).getAsInt();
        } catch (NumberFormatException | ClassCastException e) {
            return 0;
        }
    }
}
