package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.FormacraftMod;

import java.util.ArrayList;
import java.util.List;

/**
 * MaterialVariationPostProcessor（材质变化后处理器）
 * 
 * 根据风格配置和随机性，对 BlockPatch 的材质进行变化，
 * 使建筑看起来更加自然和真实。
 * 
 * 功能：
 * - 根据风格配置调整材质
 * - 添加材质变化（例如：石头有裂纹、有苔藓）
 * - 保持语义一致性（相同 SemanticPart 使用相似的材质）
 */
public class MaterialVariationPostProcessor implements PostProcessor {

    @Override
    public List<BlockPatch> process(List<BlockPatch> patches, PostProcessContext context) {
        if (patches == null || patches.isEmpty()) {
            return patches;
        }

        List<BlockPatch> result = new ArrayList<>(patches.size());

        // 对每个 patch 进行材质变化
        for (BlockPatch patch : patches) {
            if (patch == null) continue;
            if (BlockPatch.REMOVE.equals(patch.action())) {
                result.add(patch);
                continue;
            }
            String target = patch.targetBlock();
            if (target == null || target.isBlank()) {
                result.add(patch);
                continue;
            }
            String replacement = pickVariation(target, patch.dx(), patch.dy(), patch.dz());
            if (replacement == null || replacement.equals(target)) {
                result.add(patch);
            } else {
                result.add(new BlockPatch(patch.action(), patch.dx(), patch.dy(), patch.dz(), replacement));
            }
        }

        FormacraftMod.LOGGER.debug("MaterialVariationPostProcessor: processed {} patches", result.size());
        return result;
    }

    private String pickVariation(String blockId, int x, int y, int z) {
        String lower = blockId.toLowerCase();
        if (lower.contains("glass") || lower.contains("pane") || lower.contains("air")) {
            return blockId;
        }
        int hash = stableHash(x, y, z);
        int roll = Math.floorMod(hash, 100);

        if (lower.contains("stone_bricks")) {
            if (roll < 4) return "minecraft:mossy_stone_bricks";
            if (roll < 7) return "minecraft:cracked_stone_bricks";
            return blockId;
        }
        if (lower.contains("cobblestone")) {
            if (roll < 6) return "minecraft:mossy_cobblestone";
            return blockId;
        }
        if (lower.contains("deepslate_bricks")) {
            if (roll < 5) return "minecraft:cracked_deepslate_bricks";
            return blockId;
        }
        if (lower.contains("deepslate_tiles")) {
            if (roll < 4) return "minecraft:cracked_deepslate_tiles";
            return blockId;
        }

        return blockId;
    }

    private static int stableHash(int x, int y, int z) {
        int h = x * 734287 + y * 912271 + z * 438289;
        h ^= (h >>> 11);
        h *= 1103515245;
        return h;
    }
}

