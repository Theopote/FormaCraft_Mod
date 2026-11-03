package com.formacraft.server.network;

import com.formacraft.common.component.community.CommunityPackages;
import com.formacraft.common.network.CommunityTaskNetworking;
import com.formacraft.server.component.CommunityTaskBridge;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

final class CommunityTaskHandler {
    private CommunityTaskHandler() {}
    private static boolean registered;
    static synchronized void register() {
        if (registered) return; registered = true;
        ServerPlayNetworking.registerGlobalReceiver(CommunityTaskNetworking.Request.ID,(request,context) -> {
            var player = context.player(); if (player == null) return;
            var owner = player.getUuid(); var permit = CommunityTaskService.admit(context.server(),owner); if (permit == null) return;
            try {
                context.server().execute(() -> {
                    try (permit) {
                        // An old connection's queued OPEN must not recreate a session after reconnect.
                        if (context.server().getPlayerManager().getPlayer(owner) != player || !ServerPlayNetworking.canSend(player,CommunityTaskNetworking.Response.ID)) return;
                        CommunityTaskNetworking.Response response;
                        try { response = CommunityTaskService.bridgeForRequest(context.server(),request.operation()).handle(owner,request); }
                        catch (Exception failure) { response = CommunityTaskBridge.error(request,failure instanceof CommunityPackages.Failure known ? known.code() : "community_unavailable"); }
                        ServerPlayNetworking.send(player,response);
                    }
                });
            } catch (RuntimeException stopped) { permit.close(); }
        });
    }
}
