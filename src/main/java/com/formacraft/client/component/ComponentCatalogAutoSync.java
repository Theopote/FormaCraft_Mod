package com.formacraft.client.component;

import com.formacraft.common.network.FormaCraftNetworking;
import net.minecraft.client.MinecraftClient;

/**
 * 客户端自动同步构件目录（v1）：进世界后请求一次 catalog。
 */
public final class ComponentCatalogAutoSync {
    private ComponentCatalogAutoSync() {}

    private static int lastConnectionHash = 0;
    private static boolean requested = false;

    public static void tick(MinecraftClient client) {
        if (client == null || client.world == null || client.getNetworkHandler() == null) {
            requested = false;
            lastConnectionHash = 0;
            return;
        }

        int h = System.identityHashCode(client.getNetworkHandler());
        if (h != lastConnectionHash) {
            lastConnectionHash = h;
            requested = false;
        }

        if (requested) return;
        requested = true;
        FormaCraftNetworking.sendComponentCatalogRequest();
    }
}

