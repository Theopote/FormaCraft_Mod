package com.formacraft.server.preview;

import com.formacraft.FormacraftMod;
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
    private static final Map<UUID, Object> skeletonLayouts = new HashMap<>();

    /**
     * 存储玩家的建筑结构
     */
    public static void storeStructure(ServerPlayerEntity player, GeneratedStructure structure) {
        if (player != null && structure != null) {
            lastStructures.put(player.getUuid(), structure);
            hasPreview.put(player.getUuid(), false);
            skeletonLayouts.remove(player.getUuid());
            FormacraftMod.LOGGER.debug("Stored preview structure for player {}: {} blocks at {}", 
                    player.getName().getString(), 
                    structure.getBlocks() != null ? structure.getBlocks().size() : 0,
                    structure.getOrigin());
        }
    }

    /**
     * 更新玩家的预览结构（不改变 preview active 状态）
     */
    public static void updateStructure(ServerPlayerEntity player, GeneratedStructure structure) {
        if (player != null && structure != null) {
            lastStructures.put(player.getUuid(), structure);
        }
    }
    
    /**
     * 验证预览结构是否仍然有效（用于确认建造前检查）
     * @param player 玩家
     * @return true 如果预览结构有效，false 如果无效或不存在
     */
    public static boolean validatePreview(ServerPlayerEntity player) {
        if (player == null) return false;
        UUID uuid = player.getUuid();
        GeneratedStructure structure = lastStructures.get(uuid);
        if (structure == null) {
            FormacraftMod.LOGGER.warn("Player {} has no preview structure stored", player.getName().getString());
            return false;
        }
        if (structure.getBlocks() == null || structure.getBlocks().isEmpty()) {
            FormacraftMod.LOGGER.warn("Player {} preview structure has no blocks", player.getName().getString());
            return false;
        }
        if (structure.getOrigin() == null) {
            FormacraftMod.LOGGER.warn("Player {} preview structure has no origin", player.getName().getString());
            return false;
        }
        return true;
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
            skeletonLayouts.remove(uuid);
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

    public static void setSkeletonLayout(ServerPlayerEntity player, Object layout) {
        if (player == null) return;
        UUID uuid = player.getUuid();
        if (layout == null) {
            skeletonLayouts.remove(uuid);
        } else {
            skeletonLayouts.put(uuid, layout);
        }
    }

    public static Object getSkeletonLayout(ServerPlayerEntity player) {
        if (player == null) {
            return null;
        }
        return skeletonLayouts.get(player.getUuid());
    }
}

