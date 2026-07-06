package com.formacraft.common.detail;

import com.formacraft.common.generation.component.util.ComponentFloorCorniceDecorator;

import java.util.Locale;

/**
 * Maps palette block ids to stair/slab variants for detail rules.
 */
public final class DetailRuleBlockResolver {

    private DetailRuleBlockResolver() {}

    public static String resolveBlock(
            DetailRule.DetailRuleAction action,
            String paletteBlock,
            net.minecraft.util.math.Direction outward
    ) {
        if (action == null || action.type() == null) {
            return paletteBlock;
        }
        return switch (action.type()) {
            case INVERTED_STAIRS -> ComponentFloorCorniceDecorator.corniceStairBlock(paletteBlock, outward);
            case SLAB -> slabBlock(action.blockId(), paletteBlock);
            case BLOCK -> action.blockId() != null && !action.blockId().isBlank()
                    ? action.blockId().trim()
                    : paletteBlock;
        };
    }

    public static String slabBlock(String explicit, String paletteBlock) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim();
        }
        return inferSlabBlock(paletteBlock);
    }

    public static String inferSlabBlock(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return "minecraft:stone_brick_slab";
        }
        String base = blockId.trim();
        int bracket = base.indexOf('[');
        if (bracket > 0) {
            base = base.substring(0, bracket);
        }
        if (base.endsWith("_slab")) {
            return base;
        }
        if (!base.startsWith("minecraft:")) {
            return "minecraft:stone_brick_slab";
        }
        String name = base.substring("minecraft:".length());
        if (name.endsWith("_stairs")) {
            name = name.substring(0, name.length() - "_stairs".length()) + "_slab";
        } else if (name.endsWith("_planks")) {
            name = name.substring(0, name.length() - "_planks".length()) + "_slab";
        } else if (name.equals("smooth_sandstone") || name.equals("cut_sandstone") || name.equals("chiseled_sandstone")) {
            name = "sandstone_slab";
        } else if (name.equals("smooth_quartz") || name.equals("quartz_block")) {
            name = "quartz_slab";
        } else if (name.equals("deepslate_tiles") || name.equals("deepslate_bricks")) {
            name = "deepslate_tile_slab";
        } else if (name.endsWith("_bricks")) {
            String prefix = name.substring(0, name.length() - "_bricks".length());
            name = "quartz".equals(prefix) ? "quartz_slab" : prefix + "_brick_slab";
        } else if (name.equals("bricks")) {
            name = "brick_slab";
        } else if (name.endsWith("_tiles")) {
            name = name.substring(0, name.length() - "_tiles".length()) + "_tile_slab";
        } else if (!name.endsWith("_slab")) {
            name = name + "_slab";
        }
        return "minecraft:" + name;
    }

    public static boolean matchesBlockFilter(String blockId, String filter) {
        if (blockId == null || blockId.isBlank() || "minecraft:air".equals(blockId)) {
            return false;
        }
        String f = filter == null ? "wall" : filter.trim().toLowerCase(Locale.ROOT);
        if (f.isEmpty() || "wall".equals(f) || "solid_wall".equals(f)) {
            return ComponentFloorCorniceDecorator.isCorniceCandidateBlock(blockId);
        }
        if ("any".equals(f) || "solid".equals(f)) {
            String lower = blockId.toLowerCase(Locale.ROOT);
            return !lower.contains("air") && !lower.contains("glass");
        }
        return ComponentFloorCorniceDecorator.isCorniceCandidateBlock(blockId);
    }
}
