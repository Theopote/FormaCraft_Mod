package com.formacraft.common.component.variant;

import com.formacraft.common.component.model.ComponentPrototype;
import com.formacraft.common.style.profile.BlockPalette;
import com.formacraft.common.style.profile.StyleProfile;

import java.util.Map;
import java.util.Random;

/**
 * SemanticMaterialApplier（语义材质替换器）v1：第二个"魔法点"。
 * <p>
 * 职责：
 * - 根据 prototype.variant_rules.material.semantic_map（例如 "FRAME" → "DOOR_FRAME"）
 * - 将 voxel 的 semantic tag 映射到 StyleProfile 的 palette key
 * - 调用 StyleProfile.palette().pick(key) 获取实际 blockState
 * <p>
 * 效果：
 * - 同一个"哥特门"在"中世纪城堡 / 暗黑地牢 / 精灵遗迹"自动换材质
 * - 但轮廓、比例、识别性不变
 */
public final class SemanticMaterialApplier {
    private SemanticMaterialApplier() {}

    /**
     * 应用语义材质替换（核心入口）。
     * <p>
     * 策略：
     * - 遍历 grid 中所有 voxel
     * - 如果 voxel 有 semantic tag（例如 "FRAME"）
     * - 查询 material.semantic_map 获取 palette key（例如 "DOOR_FRAME"）
     * - 调用 style.palette().pick(key) 获取实际 blockState
     * - 替换 voxel 的 blockState
     */
    public static void apply(
            VoxelGrid grid,
            ComponentPrototype.VariantRules.Material material,
            String materialSet,
            StyleProfile style
    ) {
        if (grid == null || material == null || style == null) return;
        if (material.semantic_map == null || material.semantic_map.isEmpty()) return;

        Random rand = new Random();
        BlockPalette palette = style.palette();
        if (palette == null) return;

        for (Voxel v : grid.all()) {
            // 尝试获取第一个 semantic tag（v1 简化：只取第一个）
            String semanticTag = getFirstSemanticTag(v);
            if (semanticTag == null) continue;

            // 查询 semantic_map（例如 "FRAME" → "DOOR_FRAME"）
            String paletteKey = material.semantic_map.get(semanticTag);
            if (paletteKey == null) continue;

            // 从 StyleProfile 获取实际 blockState
            String blockState = pickBlockFromPalette(palette, paletteKey, rand);
            if (blockState != null && !blockState.isBlank()) {
                v.setBlockState(blockState);
            }
        }
    }

    /**
     * 获取 voxel 的第一个 semantic tag（v1 简化）。
     */
    private static String getFirstSemanticTag(Voxel v) {
        if (v == null || v.semanticTags().isEmpty()) return null;
        return v.semanticTags().iterator().next();
    }

    /**
     * 从 BlockPalette 中挑选方块（v1：简单映射）。
     * <p>
     * 策略：
     * - paletteKey 匹配 BlockPalette 的字段名（例如 "wall" / "roof" / "window" / "pillar" / ...）
     * - 如果 paletteKey 不匹配，返回 null（保持原方块）
     */
    private static String pickBlockFromPalette(BlockPalette palette, String paletteKey, Random rand) {
        if (palette == null || paletteKey == null || paletteKey.isBlank()) return null;
        String key = paletteKey.trim().toLowerCase();

        // v1：直接映射到 BlockPalette 字段（后续可扩展为规则引擎或查表）
        return switch (key) {
            case "wall" -> palette.wall;
            case "roof" -> palette.roof;
            case "floor" -> palette.floor;
            case "window" -> palette.window;
            case "foundation" -> palette.foundation;
            case "trim" -> palette.trim;
            case "pillar", "column" -> palette.pillar;
            case "cap" -> palette.cap;
            // v2: 可以支持 wallVariants / roofVariants 随机挑选
            default -> null;
        };
    }
}
