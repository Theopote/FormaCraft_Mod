package com.formacraft.client.component;

import com.formacraft.common.network.CommunityTaskNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.*;
import java.io.IOException;

/** Explicit client-thread actions used by the community browser; no automatic requests. */
public final class ClientCommunityTasks {
    private ClientCommunityTasks() {}
    public static final CommunityTaskClientState STATE = new CommunityTaskClientState(System::nanoTime);
    private static boolean registered;
    public static void register() {
        if (registered) return; registered = true;
        ClientPlayConnectionEvents.JOIN.register((handler,sender,client) -> STATE.clear());
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client) -> STATE.clear());
        ClientPlayNetworking.registerGlobalReceiver(CommunityTaskNetworking.Response.ID,(response,context) -> context.client().execute(() -> STATE.accept(response)));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            var request = STATE.nextRequest(); if (request == null) return;
            if (!ClientPlayNetworking.canSend(CommunityTaskNetworking.Request.ID)) { STATE.fail("server_unsupported"); return; }
            try { ClientPlayNetworking.send(request); } catch (RuntimeException disconnected) { STATE.fail("connection_lost"); }
        });
    }
    public static void open() { STATE.open(); }
    public static void search(byte[] request) throws IOException { STATE.search(request); }
    public static void download(String releaseId,boolean resumable) throws IOException { STATE.download(releaseId,resumable); }
    public static void cancel() { STATE.cancel(); }
    public static void previewInstall() throws IOException { STATE.previewInstall(); }
    public static void confirmInstall() throws IOException { STATE.confirmInstall(); }
    public static void saveRecipe(com.formacraft.common.component.ComponentRecipeLibrary.Spec spec) throws IOException { STATE.saveRecipe(spec); }
}
