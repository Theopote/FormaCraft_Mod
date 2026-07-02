package com.formacraft.server.state;

import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.model.request.FormaRequest;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端持有的玩家禁区/保护区（Patch 预览与应用时的权威数据源）。
 */
public final class PlayerProtectedZoneStorage {
    private PlayerProtectedZoneStorage() {}

    private static final Map<UUID, List<ProtectedZone>> PER_PLAYER = new ConcurrentHashMap<>();

    public static List<ProtectedZone> get(ServerPlayerEntity player) {
        if (player == null) return List.of();
        return get(player.getUuid());
    }

    public static List<ProtectedZone> get(UUID playerId) {
        if (playerId == null) return List.of();
        List<ProtectedZone> zones = PER_PLAYER.get(playerId);
        if (zones == null || zones.isEmpty()) return List.of();
        return Collections.unmodifiableList(zones);
    }

    public static void set(ServerPlayerEntity player, List<ProtectedZone> zones) {
        if (player == null) return;
        set(player.getUuid(), zones);
    }

    public static void set(UUID playerId, List<ProtectedZone> zones) {
        if (playerId == null) return;
        if (zones == null || zones.isEmpty()) {
            PER_PLAYER.remove(playerId);
            return;
        }
        List<ProtectedZone> copy = new ArrayList<>(zones.size());
        for (ProtectedZone z : zones) {
            if (z == null || z.min() == null || z.max() == null) continue;
            copy.add(z.normalized());
        }
        if (copy.isEmpty()) {
            PER_PLAYER.remove(playerId);
        } else {
            PER_PLAYER.put(playerId, List.copyOf(copy));
        }
    }

    /** 从 FormaRequest 同步禁区（建造请求链路已与客户端工具状态对齐）。 */
    public static void syncFromRequest(ServerPlayerEntity player, FormaRequest req) {
        if (player == null || req == null) return;
        List<ProtectedZone> zones = req.getProtectedZones();
        if (zones != null && !zones.isEmpty()) {
            set(player, zones);
        }
    }

    public static void clear(ServerPlayerEntity player) {
        if (player == null) return;
        PER_PLAYER.remove(player.getUuid());
    }
}
