package com.formacraft.client.component;

import com.formacraft.common.component.*;
import com.formacraft.common.network.ComponentLibraryNetworking;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import java.util.*;

/** At most one bounded background download; explicit user operations take priority. */
public final class ClientComponentThumbnails {
    private static final LinkedHashMap<String,ComponentCatalog.Entry> QUEUE = new LinkedHashMap<>();
    private static final ComponentLibraryClientState STATE = new ComponentLibraryClientState(System::nanoTime);
    private ClientComponentThumbnails() {}
    public static boolean request(String id) {
        var catalog = ClientComponentCatalogState.getCatalog();
        if (STATE.busy() && id.equals(STATE.assetId()) || QUEUE.containsKey(id)) return true;
        if (QUEUE.size() >= 24 || catalog == null || catalog.components == null) return false;
        var entry = catalog.components.stream().filter(e -> e != null && id.equals(e.id) && e.packageRef != null).findFirst().orElse(null);
        if (entry == null) return false;
        QUEUE.put(id,entry); return true;
    }
    public static void pause() {
        if (STATE.busy()) {
            sendCancel(); ComponentThumbnailCache.remoteFailed(STATE.assetId()); STATE.clear();
        }
    }
    public static void register() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client) -> { QUEUE.clear(); STATE.clear(); ComponentThumbnailCache.clearAllCache(); });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (STATE.expire()) { sendCancel(); ComponentThumbnailCache.remoteFailed(STATE.assetId()); STATE.clear(); }
            if (STATE.busy() || ClientComponentLibraryOperations.STATE.busy() || client.getNetworkHandler() == null || QUEUE.isEmpty()) return;
            var item = QUEUE.entrySet().iterator().next(); var entry = item.getValue(); QUEUE.remove(item.getKey());
            if (!ClientPlayNetworking.canSend(ComponentLibraryNetworking.Request.ID)) { ComponentThumbnailCache.remoteFailed(entry.id); return; }
            try { ClientPlayNetworking.send(STATE.begin(entry.id,entry.packageRef,0,entry.packageRef,false)); }
            catch (RuntimeException failed) { ComponentThumbnailCache.remoteFailed(entry.id); STATE.clear(); }
        });
    }
    public static void accept(ComponentLibraryNetworking.Response response) {
        if (!STATE.busy()) return;
        var next = STATE.accept(response);
        if (next != null) {
            try { ClientPlayNetworking.send(next); }
            catch (RuntimeException failed) { STATE.fail("Thumbnail download failed"); }
        }
        if (STATE.phase() == ComponentLibraryClientState.Phase.READY) {
            try {
                var verified = ComponentPackageIO.inspectBytes(STATE.bytes());
                if (!STATE.sourceRef().equals(verified.contentRef())) throw new java.io.IOException("Thumbnail identity mismatch");
                ComponentThumbnailCache.acceptDefinition(verified.runtimeComponent(STATE.assetId()));
            } catch (Exception invalid) { ComponentThumbnailCache.remoteFailed(STATE.assetId()); }
            STATE.clear();
        } else if (STATE.phase() == ComponentLibraryClientState.Phase.ERROR) {
            sendCancel(); ComponentThumbnailCache.remoteFailed(STATE.assetId()); STATE.clear();
        }
    }
    private static void sendCancel() {
        try { if (STATE.cancellation() != null && ClientPlayNetworking.canSend(ComponentLibraryNetworking.Request.ID)) ClientPlayNetworking.send(STATE.cancellation()); }
        catch (RuntimeException disconnected) { /* Expiry releases server memory. */ }
    }
}
