package com.formacraft.server.network;

import com.formacraft.common.component.ComponentPackageIO;
import com.formacraft.common.component.community.CommunityPackages;
import com.formacraft.server.component.CommunityTaskCoordinator;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.*;

/** Trusted configuration entry only. Registering lifecycle cleanup never enables network access. */
public final class CommunityTaskService {
    private CommunityTaskService() {}
    private static final Map<MinecraftServer,CommunityTaskCoordinator> SERVICES = new IdentityHashMap<>();
    private static boolean registered;
    static synchronized void register() {
        if (registered) return; registered = true;
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server) -> {
            var service = find(server); if (service != null) service.disconnect(handler.getPlayer().getUuid());
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> { var service = find(server); if (service != null) service.expire(); });
        ServerLifecycleEvents.SERVER_STOPPED.register(CommunityTaskService::disable);
    }
    public static void configure(MinecraftServer server, String authority, URI base) throws IOException {
        if (!server.isOnThread()) throw new IllegalStateException("Community configuration requires server thread");
        var next = new CommunityTaskCoordinator(server.getSavePath(WorldSavePath.ROOT),authority,base,ComponentPackageIO.currentEnvironment(),Clock.systemUTC());
        CommunityTaskCoordinator previous;
        synchronized (CommunityTaskService.class) { previous = SERVICES.put(server,next); }
        if (previous != null) previous.close();
    }
    public static CommunityTaskCoordinator get(MinecraftServer server) throws IOException {
        var service = find(server); if (service == null) throw new CommunityPackages.Failure("community_disabled"); return service;
    }
    private static synchronized CommunityTaskCoordinator find(MinecraftServer server) { return SERVICES.get(server); }
    public static void disable(MinecraftServer server) {
        CommunityTaskCoordinator service; synchronized (CommunityTaskService.class) { service = SERVICES.remove(server); }
        if (service != null) service.close();
    }
}
