package com.formacraft.common.palette.dynamic;

import com.formacraft.common.patch.BlockPatch;

import java.util.Locale;

/**
 * Remap gravity / falling blocks to similar-looking stable blocks so generated
 * structures do not collapse after placement.
 */
public final class GravityBlockPolicy {
    private GravityBlockPolicy() {}

    /** Return a non-falling substitute for known gravity block ids; otherwise the input. */
    public static String stabilize(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return blockId;
        }
        String raw = blockId.trim();
        String base = raw;
        String props = "";
        int lb = raw.indexOf('[');
        if (lb >= 0) {
            base = raw.substring(0, lb);
            props = raw.substring(lb);
        }
        String id = base.toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (!id.contains(":")) {
            id = "minecraft:" + id;
        }
        String replacement = switch (id) {
            case "minecraft:gravel" -> "minecraft:cobblestone";
            case "minecraft:sand" -> "minecraft:sandstone";
            case "minecraft:red_sand" -> "minecraft:red_sandstone";
            case "minecraft:suspicious_sand" -> "minecraft:sandstone";
            case "minecraft:suspicious_gravel" -> "minecraft:cobblestone";
            default -> null;
        };
        if (replacement == null && id.endsWith("_concrete_powder")) {
            replacement = id.substring(0, id.length() - "_powder".length());
        }
        if (replacement == null) {
            return raw;
        }
        return replacement + props;
    }

    public static BlockPatch stabilize(BlockPatch patch) {
        if (patch == null || patch.targetBlock() == null) {
            return patch;
        }
        String stable = stabilize(patch.targetBlock());
        if (stable.equals(patch.targetBlock())) {
            return patch;
        }
        return new BlockPatch(patch.action(), patch.dx(), patch.dy(), patch.dz(), stable, patch.source());
    }
}
