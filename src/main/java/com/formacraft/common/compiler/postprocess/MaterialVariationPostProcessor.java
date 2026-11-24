package com.formacraft.common.compiler.postprocess;

import com.formacraft.FormacraftMod;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.style.AestheticPassHints;

import java.util.ArrayList;
import java.util.List;

/**
 * Height-aware weathering / material micro-variation.
 */
public class MaterialVariationPostProcessor implements PostProcessor {

    @Override
    public PostProcessWritePolicy writePolicy() {
        return PostProcessWritePolicy.REPLACE_WITHIN_SCOPE;
    }

    @Override
    public List<BlockPatch> process(List<BlockPatch> patches, PostProcessContext context) {
        if (patches == null || patches.isEmpty()) {
            return patches;
        }

        AestheticPassHints.Resolved hints = AestheticPassHints.resolve(
                context != null ? context.plan() : null, null);
        if (hints.weathering() == AestheticPassHints.Weathering.NONE) {
            return patches;
        }

        int baseMinY = Integer.MAX_VALUE;
        if (context != null && context.buildingVolumes() != null) {
            for (var v : context.buildingVolumes()) {
                baseMinY = Math.min(baseMinY, v.bounds().minY());
            }
        }
        if (baseMinY == Integer.MAX_VALUE) {
            for (BlockPatch p : patches) {
                if (p != null) baseMinY = Math.min(baseMinY, p.dy());
            }
        }
        if (baseMinY == Integer.MAX_VALUE) baseMinY = 0;

        int nearBoost = switch (hints.weathering()) {
            case SUBTLE -> 12;
            case MEDIUM -> 16;
            case HEAVY -> 22;
            default -> 0;
        };
        int midRate = switch (hints.weathering()) {
            case SUBTLE -> 5;
            case MEDIUM -> 7;
            case HEAVY -> 10;
            default -> 0;
        };

        List<BlockPatch> result = new ArrayList<>(patches.size());
        for (BlockPatch patch : patches) {
            if (patch == null) continue;
            var pos = new net.minecraft.util.math.BlockPos(patch.dx(), patch.dy(), patch.dz());
            // Skip user-locked materials and circulation clearance — WriteScope denies PLACE
            // on clearance, and weathering an existing seed there yields E_POSTPROCESS_PATCH_CONFLICT.
            if (context != null && (context.protectedMaterials().contains(pos)
                    || context.protectedClearance().contains(pos))) {
                result.add(patch);
                continue;
            }
            if (BlockPatch.REMOVE.equals(patch.action())) {
                result.add(patch);
                continue;
            }
            String target = patch.targetBlock();
            if (target == null || target.isBlank()) {
                result.add(patch);
                continue;
            }
            int relY = patch.dy() - baseMinY;
            int threshold = relY <= 2 ? nearBoost : midRate;
            String replacement = pickVariation(target, patch.dx(), patch.dy(), patch.dz(), threshold, hints.weathering());
            if (replacement == null || replacement.equals(target)) {
                result.add(patch);
            } else {
                result.add(new BlockPatch(patch.action(), patch.dx(), patch.dy(), patch.dz(), replacement));
            }
        }

        FormacraftMod.LOGGER.debug("MaterialVariationPostProcessor: processed {} patches", result.size());
        return result;
    }

    private String pickVariation(String blockId, int x, int y, int z, int threshold,
                                 AestheticPassHints.Weathering weathering) {
        String lower = blockId.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("glass") || lower.contains("pane") || lower.contains("air")
                || lower.contains("door") || lower.contains("lantern") || lower.contains("torch")) {
            return blockId;
        }
        int roll = Math.floorMod(stableHash(x, y, z), 100);
        if (roll >= threshold) return blockId;

        if (lower.equals("minecraft:stone_bricks")) {
            return roll % 2 == 0 ? "minecraft:mossy_stone_bricks" : "minecraft:cracked_stone_bricks";
        }
        if (lower.equals("minecraft:cobblestone")) {
            return "minecraft:mossy_cobblestone";
        }
        if (lower.equals("minecraft:deepslate_bricks")) {
            return "minecraft:cracked_deepslate_bricks";
        }
        if (lower.equals("minecraft:deepslate_tiles")) {
            return "minecraft:cracked_deepslate_tiles";
        }
        if (lower.equals("minecraft:bricks")) {
            return roll % 2 == 0 ? "minecraft:granite" : "minecraft:terracotta";
        }
        if (lower.equals("minecraft:sandstone")) {
            return roll % 2 == 0 ? "minecraft:smooth_sandstone" : "minecraft:cut_sandstone";
        }
        if (lower.equals("minecraft:polished_andesite")) {
            return roll % 2 == 0 ? "minecraft:andesite" : "minecraft:stone";
        }
        if (lower.equals("minecraft:oak_planks") && weathering.ordinal() >= AestheticPassHints.Weathering.MEDIUM.ordinal()) {
            return "minecraft:stripped_oak_log";
        }
        if (lower.equals("minecraft:white_concrete")
                && weathering.ordinal() >= AestheticPassHints.Weathering.MEDIUM.ordinal()) {
            return roll % 2 == 0 ? "minecraft:calcite" : "minecraft:diorite";
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
