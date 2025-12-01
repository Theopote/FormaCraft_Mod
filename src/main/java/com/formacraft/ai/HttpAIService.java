package com.formacraft.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.formacraft.common.lang.StructureData;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.util.math.BlockPos;

public class HttpAIService implements AIService {

    private final String baseUrl = "http://localhost:8000/api/v1/generate_building_plan";
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
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String body = response.body();
                StructureData structureData = parseStructureData(body);
                return new AIResult(body, structureData);
            }
        } catch (IOException | InterruptedException e) {
            // TODO: 可在此处加入日志记录
        }

        return fallback.generateBuildingPlan(request);
    }

    private String buildRequestJson(BuildingRequest request) {
        String prompt = escapeJson(request.getPrompt());
        BlockPos origin = request.getOrigin();
        String dimension = escapeJson(request.getDimensionId());
        String sessionId = request.getSessionId() != null ? escapeJson(request.getSessionId()) : null;

        int x = origin != null ? origin.getX() : 0;
        int y = origin != null ? origin.getY() : 0;
        int z = origin != null ? origin.getZ() : 0;

        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"prompt\":\"").append(prompt).append("\",");
        sb.append("\"origin\":{\"x\":").append(x).append(",\"y\":").append(y).append(",\"z\":").append(z).append("},");
        sb.append("\"dimension\":\"").append(dimension).append("\"");

        if (sessionId != null && !sessionId.isEmpty()) {
            sb.append(',').append("\"session_id\":\"").append(sessionId).append("\"");
        }

        // chat_history as JSON array of strings
        if (request.getChatHistory() != null && !request.getChatHistory().isEmpty()) {
            sb.append(',').append("\"chat_history\": [");
            boolean first = true;
            for (String line : request.getChatHistory()) {
                if (!first) sb.append(',');
                String escaped = escapeJson(line == null ? "" : line);
                sb.append('"').append(escaped).append('"');
                first = false;
            }
            sb.append(']');
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

    private StructureData parseStructureData(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JsonObject root = gson.fromJson(json, JsonObject.class);
            if (root == null) return null;
            JsonObject structure = root.has("structure") && root.get("structure").isJsonObject()
                    ? root.getAsJsonObject("structure") : null;
            if (structure == null) return null;

            String type = structure.has("type") && structure.get("type").isJsonPrimitive()
                    ? structure.get("type").getAsString() : null;
            String material = structure.has("material") && structure.get("material").isJsonPrimitive()
                    ? structure.get("material").getAsString() : null;
            int towers = structure.has("towers") && structure.get("towers").isJsonPrimitive()
                    ? safeGetInt(structure, "towers") : 0;
            String style = structure.has("style") && structure.get("style").isJsonPrimitive()
                    ? structure.get("style").getAsString() : null;

            JsonObject dims = root.has("dimensions") && root.get("dimensions").isJsonObject()
                    ? root.getAsJsonObject("dimensions") : null;
            int width = dims != null ? safeGetInt(dims, "width") : 0;
            int height = dims != null ? safeGetInt(dims, "height") : 0;
            int depth = dims != null ? safeGetInt(dims, "depth") : 0;

            if (type == null && material == null && style == null && towers == 0 && width == 0 && height == 0 && depth == 0) {
                return null;
            }
            return new StructureData(
                    type != null ? type : "unknown",
                    material != null ? material : "stone",
                    Math.max(0, towers),
                    style != null ? style : "default",
                    Math.max(0, width),
                    Math.max(0, height),
                    Math.max(0, depth));
        } catch (JsonParseException e) {
            return null;
        }
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
