package com.formacraft.common.generation.component.util;

import net.minecraft.util.math.Direction;

/**
 * Pure string helpers for oriented sub-voxel blocks (safe in JVM unit tests).
 */
public final class SubVoxelBlockStates {
    private SubVoxelBlockStates() {}

    /** Stairs facing {@code outwardDir}; {@code topHalf} → half=top (lintel/cornice). */
    public static String outwardStairs(String stairsId, Direction outwardDir, boolean topHalf) {
        if (stairsId == null || stairsId.isBlank()) return null;
        String base = stripState(stairsId);
        Direction facing = outwardDir != null ? outwardDir : Direction.SOUTH;
        // Minecraft stairs face the direction of the lower step edge; cornice uses opposite of outward.
        Direction stairFacing = facing.getOpposite();
        return base + "[facing=" + stairFacing.asString() + ",half=" + (topHalf ? "top" : "bottom")
                + ",shape=straight]";
    }

    public static String slab(String slabId, boolean topHalf) {
        if (slabId == null || slabId.isBlank()) return null;
        return stripState(slabId) + "[type=" + (topHalf ? "top" : "bottom") + "]";
    }

    /** Open shutter on the exterior face. */
    public static String shutterTrapdoor(String trapdoorId, Direction outwardDir, boolean open) {
        if (trapdoorId == null || trapdoorId.isBlank()) return null;
        Direction facing = outwardDir != null ? outwardDir : Direction.SOUTH;
        return stripState(trapdoorId) + "[facing=" + facing.asString()
                + ",half=bottom,open=" + open + ",powered=false,waterlogged=false]";
    }

    public static String hangingLantern(boolean soul) {
        return (soul ? "minecraft:soul_lantern" : "minecraft:lantern") + "[hanging=true,waterlogged=false]";
    }

    public static String standingLantern(boolean soul) {
        return (soul ? "minecraft:soul_lantern" : "minecraft:lantern") + "[hanging=false,waterlogged=false]";
    }

    public static String wallTorch(Direction outwardDir) {
        Direction facing = outwardDir != null ? outwardDir : Direction.SOUTH;
        return "minecraft:wall_torch[facing=" + facing.asString() + "]";
    }

    private static String stripState(String id) {
        int bracket = id.indexOf('[');
        return bracket >= 0 ? id.substring(0, bracket) : id;
    }
}
