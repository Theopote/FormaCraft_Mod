package com.formacraft.client.component;

import com.formacraft.client.network.FormaCraftClientNetworking;
import net.minecraft.client.MinecraftClient;

/**
 * 客户端自动同步构件目录：进世界后请求 catalog，失败/超时时有限重试。
 */
public final class ComponentCatalogAutoSync {
    private ComponentCatalogAutoSync() {}

    private static final long RETRY_INTERVAL_MS = 5_000L;
    private static final int MAX_RETRIES = 3;

    private static int lastConnectionHash = 0;
    private static boolean requestedThisCycle = false;
    private static long lastRequestMs = 0L;
    private static int retryCount = 0;

    public static void tick(MinecraftClient client) {
        if (client == null || client.world == null || client.getNetworkHandler() == null) {
            resetSession();
            return;
        }

        int h = System.identityHashCode(client.getNetworkHandler());
        if (h != lastConnectionHash) {
            resetSession();
            lastConnectionHash = h;
        }

        long now = System.currentTimeMillis();
        if (ClientComponentCatalogState.isSyncComplete()) {
            return;
        }

        ClientComponentCatalogState.hydrateFromDiskIfEmpty();
        if (requestedThisCycle
                && now - lastRequestMs >= RETRY_INTERVAL_MS
                && retryCount < MAX_RETRIES) {
            requestedThisCycle = false;
            retryCount++;
        }

        if (requestedThisCycle) {
            return;
        }

        requestedThisCycle = true;
        lastRequestMs = now;
        ClientComponentCatalogState.markSyncRequested();
        FormaCraftClientNetworking.sendComponentCatalogRequest();
    }

    private static void resetSession() {
        requestedThisCycle = false;
        lastConnectionHash = 0;
        lastRequestMs = 0L;
        retryCount = 0;
    }
}
