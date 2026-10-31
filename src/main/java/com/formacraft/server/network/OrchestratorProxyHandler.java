package com.formacraft.server.network;

import com.formacraft.FormacraftMod;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.server.orchestrator.OrchestratorClient;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Allowlisted server-side HTTP proxy so multiplayer clients can reach /models and
 * /summarize using the dedicated server's orchestrator secret.
 */
public final class OrchestratorProxyHandler {
    private static final int MAX_BODY_CHARS = 30_000;

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

        java.util.concurrent.CompletableFuture.supplyAsync(() -> execute(op, raw))
                .whenComplete((result, err) -> {
                    OrchestratorClient.ProxyHttpResult r = result;
                    if (err != null) {
                        FormacraftMod.LOGGER.warn("orchestrator proxy failed op={} player={}",
                                op, player.getUuid(), err);
                        r = OrchestratorClient.ProxyHttpResult.of(-1, err.toString());
                    }
                    OrchestratorClient.ProxyHttpResult finalResult = r == null
                            ? OrchestratorClient.ProxyHttpResult.of(-1, "null result")
                            : r;
                    String body = finalResult.body();
                    if (body != null && body.length() > MAX_BODY_CHARS) {
                        body = body.substring(0, MAX_BODY_CHARS - 1) + "…";
                    }
                    String bodyOut = body;
                    server.execute(() -> {
                        if (player.networkHandler == null) {
                            return;
                        }
                        ServerPlayNetworking.send(player,
                                new FormaCraftNetworking.OrchestratorProxyResponsePayload(
                                        requestId, finalResult.status(), bodyOut));
                    });
                });
    }

    static OrchestratorClient.ProxyHttpResult execute(String op, String raw) {
        OrchestratorClient client = NetworkOrchestratorProvider.get();
        return switch (op) {
            case "models" -> {
                ParsedModelsRequest req = ParsedModelsRequest.parse(raw);
                yield client.proxyGetModels(req.query(), req.bearer());
            }
            case "summarize" -> client.proxySummarize(raw.isBlank() ? "{\"transcript\":\"\"}" : raw);
            default -> OrchestratorClient.ProxyHttpResult.of(400, "unsupported proxy op: " + op);
        };
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
