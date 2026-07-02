package com.formacraft.server.state;

import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.model.request.FormaRequest;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端持有的玩家轮廓/Footprint（Patch 预览过滤的权威数据源之一）。
 */
public final class PlayerOutlineStorage {
    private PlayerOutlineStorage() {}

    private static final Map<UUID, OutlineShape> PER_PLAYER = new ConcurrentHashMap<>();

    public static OutlineShape get(ServerPlayerEntity player) {
        if (player == null) return null;
        return PER_PLAYER.get(player.getUuid());
    }

    public static void set(ServerPlayerEntity player, OutlineShape outline) {
        if (player == null) return;
        set(player.getUuid(), outline);
    }

    public static void set(UUID playerId, OutlineShape outline) {
        if (playerId == null) return;
        if (outline == null) {
            PER_PLAYER.remove(playerId);
        } else {
            PER_PLAYER.put(playerId, outline);
        }
    }

    public static void syncFromRequest(ServerPlayerEntity player, FormaRequest req) {
        if (player == null || req == null) return;
        OutlineShape outline = req.getOutline();
        if (outline != null) {
            set(player, outline);
        }
    }

    public static void clear(ServerPlayerEntity player) {
        if (player == null) return;
        PER_PLAYER.remove(player.getUuid());
    }
}
