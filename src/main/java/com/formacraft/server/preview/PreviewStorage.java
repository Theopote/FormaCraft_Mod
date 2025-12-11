package com.formacraft.server.preview;

import com.formacraft.server.build.GeneratedStructure;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 预览存储
 * 临时存储玩家最后一次生成的建筑结构，用于预览和确认
 */
public class PreviewStorage {
    private static final Map<UUID, GeneratedStructure> lastStructures = new HashMap<>();
    private static final Map<UUID, Boolean> hasPreview = new HashMap<>();

    /**
     * 存储玩家的建筑结构
     */
    public static void storeStructure(ServerPlayerEntity player, GeneratedStructure structure) {
        if (player != null && structure != null) {
            lastStructures.put(player.getUuid(), structure);
            hasPreview.put(player.getUuid(), false);
        }
    }

    /**
     * 获取玩家的建筑结构
     */
    public static GeneratedStructure getStructure(ServerPlayerEntity player) {
        if (player == null) {
            return null;
        }
        return lastStructures.get(player.getUuid());
    }

    /**
     * 清除玩家的预览数据
     */
    public static void clear(ServerPlayerEntity player) {
        if (player != null) {
            UUID uuid = player.getUuid();
            lastStructures.remove(uuid);
            hasPreview.remove(uuid);
        }
    }

    /**
     * 检查玩家是否有预览
     */
    public static boolean hasPreview(ServerPlayerEntity player) {
        if (player == null) {
            return false;
        }
        return hasPreview.getOrDefault(player.getUuid(), false);
    }

    /**
     * 设置玩家预览状态
     */
    public static void setPreview(ServerPlayerEntity player, boolean active) {
        if (player != null) {
            hasPreview.put(player.getUuid(), active);
        }
    }
}

