package com.formacraft.client.network;

import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.common.network.OrchestratorEndpointAccess;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Client helper for allowlisted orchestrator hops via the connected game server.
 */
public final class OrchestratorProxyClient {
    private OrchestratorProxyClient() {}

    private static final Map<String, CompletableFuture<FormaCraftNetworking.OrchestratorProxyResponsePayload>> PENDING =
            new ConcurrentHashMap<>();

    public static void onResponse(FormaCraftNetworking.OrchestratorProxyResponsePayload payload) {
        if (payload == null || payload.requestId() == null) return;
        CompletableFuture<FormaCraftNetworking.OrchestratorProxyResponsePayload> fut =
                PENDING.remove(payload.requestId());
        if (fut != null) {
            fut.complete(payload);
        }
    }

    public static boolean canUsePlayProxy() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null && mc.getNetworkHandler() != null;
    }

    /**
     * Prefer direct HTTP for localhost/with-secret; otherwise C2S proxy when in-game.
     */
    public static CompletableFuture<FormaCraftNetworking.OrchestratorProxyResponsePayload> models(
            String endpointForDirectnessCheck,
            String query,
            String bearer
    ) {
        if (OrchestratorEndpointAccess.clientMayCallDirectly(endpointForDirectnessCheck)
                || !canUsePlayProxy()) {
            return CompletableFuture.completedFuture(null); // signal caller to use direct HTTP
        }
        JsonObject body = new JsonObject();
        body.addProperty("query", query == null ? "" : query);
        if (bearer != null && !bearer.isBlank()) {
            body.addProperty("bearer", bearer);
        }
        return request("models", body.toString());
    }

    public static CompletableFuture<FormaCraftNetworking.OrchestratorProxyResponsePayload> summarize(
            String endpointForDirectnessCheck,
            String jsonBody
    ) {
        if (OrchestratorEndpointAccess.clientMayCallDirectly(endpointForDirectnessCheck)
                || !canUsePlayProxy()) {
            return CompletableFuture.completedFuture(null);
        }
        return request("summarize", jsonBody == null ? "" : jsonBody);
    }

    private static CompletableFuture<FormaCraftNetworking.OrchestratorProxyResponsePayload> request(
            String op, String payloadJson
    ) {
        String id = UUID.randomUUID().toString();
        CompletableFuture<FormaCraftNetworking.OrchestratorProxyResponsePayload> fut = new CompletableFuture<>();
        PENDING.put(id, fut);
        var payload = new FormaCraftNetworking.OrchestratorProxyRequestPayload(id, op, payloadJson);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new CustomPayloadC2SPacket(payload));
        } else {
            ClientPlayNetworking.send(payload);
        }
        return fut.orTimeout(15, TimeUnit.SECONDS).whenComplete((r, e) -> PENDING.remove(id));
    }
}
