package com.formacraft.server.network;

import com.formacraft.common.component.ComponentCatalogTransfer;
import com.formacraft.common.network.FormaCraftNetworking.ComponentCatalogPayload;
import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.server.network.ServerPlayerEntity;
import java.util.*;

/** Server-thread queue; a newer snapshot replaces an unfinished older one for the same player. */
public final class ComponentCatalogSync {
    private record Pending(long revision, byte[] bytes, int offset) {}
    private static final Map<UUID,Pending> QUEUE = new HashMap<>();
    private static long nextRevision;
    private static boolean registered;
    static synchronized void register() {
        if (registered) return; registered = true;
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> QUEUE.clear());
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server) -> QUEUE.remove(handler.getPlayer().getUuid()));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            var entries = QUEUE.entrySet().iterator();
            while (entries.hasNext()) {
                var entry = entries.next(); var player = server.getPlayerManager().getPlayer(entry.getKey());
                if (player == null) { entries.remove(); continue; }
                var pending = entry.getValue(); int offset = pending.offset();
                try {
                    for (int i = 0; i < 2 && offset < pending.bytes().length; i++) {
                        var chunk = ComponentCatalogTransfer.chunk(pending.revision(),pending.bytes(),offset);
                        ServerPlayNetworking.send(player,new ComponentCatalogPayload(chunk)); offset += chunk.data().length;
                    }
                } catch (RuntimeException disconnected) { entries.remove(); continue; }
                if (offset == pending.bytes().length) entries.remove();
                else entry.setValue(new Pending(pending.revision(),pending.bytes(),offset));
            }
        });
    }
    public static void send(ServerPlayerEntity player, String json) {
        if (!ServerPlayNetworking.canSend(player,ComponentCatalogPayload.ID)) return;
        long revision = ++nextRevision;
        try {
            byte[] bytes = ComponentCatalogTransfer.encode(json);
            long others = QUEUE.entrySet().stream().filter(e -> !e.getKey().equals(player.getUuid())).mapToLong(e -> e.getValue().bytes.length).sum();
            if (QUEUE.size() - (QUEUE.containsKey(player.getUuid()) ? 1 : 0) >= 4 || others+bytes.length > 32L*1024*1024) throw new java.io.IOException("目录同步队列繁忙");
            QUEUE.put(player.getUuid(),new Pending(revision,bytes,0));
        } catch (java.io.IOException tooLarge) {
            QUEUE.remove(player.getUuid());
            ServerPlayNetworking.send(player,new ComponentCatalogPayload(new ComponentCatalogTransfer.Chunk(revision,0,0,new byte[0])));
            com.formacraft.FormacraftMod.LOGGER.warn("Component catalog sync refused player={} reason={}",player.getUuid(),tooLarge.getMessage());
        }
    }
}
