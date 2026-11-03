package com.formacraft.server.network;

import com.formacraft.common.component.ComponentPackageIO;
import com.formacraft.common.component.community.CommunityPackages;
import com.formacraft.server.component.CommunityTaskCoordinator;
import com.formacraft.server.component.CommunityTaskBridge;
import com.formacraft.server.component.CommunityMessageGate;
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
    private record Service(CommunityTaskCoordinator tasks,CommunityTaskBridge bridge) {}
    private static final Map<MinecraftServer,Service> SERVICES = new IdentityHashMap<>();
    private static final Map<MinecraftServer,CommunityMessageGate> GATES = new IdentityHashMap<>();
    private static boolean registered;
    static synchronized void register() {
        if (registered) return; registered = true;
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server) -> {
            var service = find(server); if (service != null) service.bridge.disconnect(handler.getPlayer().getUuid());
            synchronized (CommunityTaskService.class) { var gate = GATES.get(server); if (gate != null) gate.disconnect(handler.getPlayer().getUuid()); }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> { var service = find(server); if (service != null) service.bridge.expire(); });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            disable(server); synchronized (CommunityTaskService.class) { var gate = GATES.remove(server); if (gate != null) gate.close(); }
        });
    }
    public static void configure(MinecraftServer server, String authority, URI base) throws IOException {
        if (!server.isOnThread()) throw new IllegalStateException("Community configuration requires server thread");
        var next = new CommunityTaskCoordinator(server.getSavePath(WorldSavePath.ROOT),authority,base,ComponentPackageIO.currentEnvironment(),Clock.systemUTC());
        Service previous;
        synchronized (CommunityTaskService.class) { previous = SERVICES.put(server,new Service(next,new CommunityTaskBridge(next,Clock.systemUTC()))); }
        if (previous != null) previous.bridge.close();
    }
    public static CommunityTaskCoordinator get(MinecraftServer server) throws IOException {
        var service = find(server); if (service == null) throw new CommunityPackages.Failure("community_disabled"); return service.tasks;
    }
    public static CommunityTaskBridge bridge(MinecraftServer server) throws IOException {
        var service = find(server); if (service == null) throw new CommunityPackages.Failure("community_disabled"); return service.bridge;
    }
    static synchronized CommunityMessageGate.Permit admit(MinecraftServer server,UUID owner) {
        return GATES.computeIfAbsent(server,ignored -> new CommunityMessageGate(System::nanoTime)).acquire(owner);
    }
    private static synchronized Service find(MinecraftServer server) { return SERVICES.get(server); }
    public static void disable(MinecraftServer server) {
        Service service; synchronized (CommunityTaskService.class) { service = SERVICES.remove(server); }
        if (service != null) service.bridge.close();
    }
}
