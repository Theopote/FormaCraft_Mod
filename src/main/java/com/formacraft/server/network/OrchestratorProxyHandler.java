package com.formacraft.server.network;

import com.formacraft.FormacraftMod;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.server.config.ServerLlmConfig;
import com.formacraft.server.orchestrator.OrchestratorClient;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Allowlisted server-side HTTP proxy so multiplayer clients can reach /models and
 * /summarize using the dedicated server's orchestrator secret.
 */
public final class OrchestratorProxyHandler {
    private static final int MAX_BODY_CHARS = 30_000;
    private static final Map<UUID, Deque<Long>> WINDOW_HITS = new ConcurrentHashMap<>();

    private OrchestratorProxyHandler() {}

    public static void handleAsync(
            MinecraftServer server,
            ServerPlayerEntity player,
            FormaCraftNetworking.OrchestratorProxyRequestPayload payload
    ) {
        if (server == null || player == null || payload == null) {
            return;
        }
        String requestId = payload.requestId() == null ? "" : payload.requestId().trim();
        String op = payload.op() == null ? "" : payload.op().trim().toLowerCase();
        String raw = payload.payloadJson() == null ? "" : payload.payloadJson();

        int required = ServerLlmConfig.requireBuildPermissionLevel(server.isDedicated());
        if (required > 0 && !player.hasPermissionLevel(required)) {
            respond(server, player, requestId, 403, "permission required: " + required);
            return;
        }
        if (!tryAdmitRate(player.getUuid())) {
            respond(server, player, requestId, 429, "rate limited");
            return;
        }

        boolean dedicated = server.isDedicated();
        boolean submitted = OrchestratorProxyWorkers.trySubmit(() -> {
            OrchestratorClient.ProxyHttpResult result;
            try {
                result = execute(op, raw, dedicated);
            } catch (Exception err) {
                FormacraftMod.LOGGER.warn("orchestrator proxy failed op={} player={}",
                        op, player.getUuid(), err);
                result = OrchestratorClient.ProxyHttpResult.of(-1, err.toString());
            }
            respond(server, player, requestId, result.status(), truncate(result.body()));
        });
        if (!submitted) {
            respond(server, player, requestId, 503, "proxy busy");
        }
    }

    static OrchestratorClient.ProxyHttpResult execute(String op, String raw) {
        return execute(op, raw, false);
    }

    static OrchestratorClient.ProxyHttpResult execute(String op, String raw, boolean dedicated) {
        OrchestratorClient client = NetworkOrchestratorProvider.get();
        return switch (op) {
            case "models" -> {
                ParsedModelsRequest req = ParsedModelsRequest.parse(raw);
                yield client.proxyGetModels(req.query(), req.bearer());
            }
            case "summarize" -> {
                String body = ServerLlmConfig.rewriteProxySummarizeJson(raw, dedicated);
                yield client.proxySummarize(body);
            }
            default -> OrchestratorClient.ProxyHttpResult.of(400, "unsupported proxy op: " + op);
        };
    }

    /** Drop per-player proxy rate-limit state (disconnect / stop). */
    public static void clearPlayer(UUID playerId) {
        if (playerId != null) {
            WINDOW_HITS.remove(playerId);
        }
    }

    public static void clearAll() {
        WINDOW_HITS.clear();
    }

    private static boolean tryAdmitRate(UUID playerId) {
        if (playerId == null) return false;
        int maxPerWindow = Math.max(1, ServerLlmConfig.buildRateLimitPerWindow());
        long windowMs = Math.max(1, ServerLlmConfig.buildRateLimitWindowSeconds()) * 1000L;
        long t = System.currentTimeMillis();
        Deque<Long> hits = WINDOW_HITS.computeIfAbsent(playerId, u -> new ArrayDeque<>());
        synchronized (hits) {
            while (!hits.isEmpty() && t - hits.peekFirst() > windowMs) {
                hits.removeFirst();
            }
            if (hits.size() >= maxPerWindow) {
                return false;
            }
            hits.addLast(t);
            return true;
        }
    }

    private static String truncate(String body) {
        if (body != null && body.length() > MAX_BODY_CHARS) {
            return body.substring(0, MAX_BODY_CHARS - 1) + "…";
        }
        return body == null ? "" : body;
    }

    private static void respond(
            MinecraftServer server,
            ServerPlayerEntity player,
            String requestId,
            int status,
            String body
    ) {
        String bodyOut = truncate(body);
        server.execute(() -> {
            if (player.networkHandler == null) {
                return;
            }
            ServerPlayNetworking.send(player,
                    new FormaCraftNetworking.OrchestratorProxyResponsePayload(
                            requestId, status, bodyOut));
        });
    }

    private record ParsedModelsRequest(String query, String bearer) {
        static ParsedModelsRequest parse(String json) {
            if (json == null || json.isBlank()) {
                return new ParsedModelsRequest("", null);
            }
            try {
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                String query = obj.has("query") && !obj.get("query").isJsonNull()
                        ? obj.get("query").getAsString() : "";
                String bearer = obj.has("bearer") && !obj.get("bearer").isJsonNull()
                        ? obj.get("bearer").getAsString() : null;
                return new ParsedModelsRequest(query, bearer);
            } catch (Exception e) {
                return new ParsedModelsRequest(json, null);
            }
        }
    }
}
