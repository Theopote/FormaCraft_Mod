package com.formacraft.server.terrain;

import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Terrain height sampling.
 * <p>
 * Prefer {@link #placementY} over raw {@link Heightmap.Type#WORLD_SURFACE}: the latter
 * treats leaves / vines / tall grass as ground and floats buildings on canopies.
 */
public class TerrainSampling {

    private static final int WORLD_MAX_Y = 384;

    /**
     * First-air Y suitable for placing a structure: MOTION_BLOCKING_NO_LEAVES, then
     * descend through logs/leaves until solid ground or fluid (same contract as
     * {@link BuildingLandingPlanner#ground(ServerWorld)}).
     */
    public static int placementY(ServerWorld world, int x, int z) {
        if (world == null) return 64;
        int bottom = world.getBottomY();
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= bottom || y > WORLD_MAX_Y) {
            y = scanFromTop(world, x, z, bottom);
        }
        return descendPastCanopy(world, x, z, y, bottom);
    }

    /**
     * Sample placement heights over an inclusive XZ rectangle.
     */
    public static List<Integer> sampleHeights(ServerWorld world, BlockPos min, BlockPos max) {
        List<Integer> heights = new ArrayList<>();
        if (world == null || min == null || max == null) return heights;

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                heights.add(placementY(world, x, z));
            }
        }
        return heights;
    }

    /**
     * Descend from a heightmap first-air Y past logs/leaves.
     * Package-visible for focused unit tests without a live ServerWorld column.
     */
    static int descendPastCanopy(ServerWorld world, int x, int z, int startY, int bottomY) {
        int y = startY;
        while (y > bottomY) {
            BlockPos below = new BlockPos(x, y - 1, z);
            BlockState s = world.getBlockState(below);
            if (isBearingGround(world, below, s)) break;
            y--;
        }
        return y;
    }

    /** True when {@code below} is solid/fluid ground that should stop canopy descent. */
    static boolean isBearingGround(ServerWorld world, BlockPos below, BlockState s) {
        if (s == null || isCanopyMaterial(s)) return false;
        return s.isSolidBlock(world, below) || !s.getFluidState().isEmpty();
    }

    /**
     * True for canopy materials that must not define building ground height.
     * Prefer BlockTags when bound; fall back to instanceof / id path for unit tests.
     */
    static boolean isCanopyMaterial(BlockState s) {
        if (s == null || s.isAir()) return false;
        var block = s.getBlock();
        if (block instanceof net.minecraft.block.LeavesBlock) return true;
        try {
            if (s.isIn(BlockTags.LEAVES) || s.isIn(BlockTags.LOGS)) return true;
        } catch (IllegalStateException unbound) {
            String path = net.minecraft.registry.Registries.BLOCK.getId(block).getPath();
            return path.contains("log") || path.contains("stem") || path.contains("hyphae")
                    || path.contains("leaves");
        }
        return false;
    }

    private static int scanFromTop(ServerWorld world, int x, int z, int bottomY) {
        int y = WORLD_MAX_Y - 1;
        while (y > bottomY) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState s = world.getBlockState(pos);
            if (!s.isAir() && !isCanopyMaterial(s)) {
                return y + 1; // first air above non-canopy material
            }
            y--;
        }
        return 64;
    }

    public static int medianHeight(List<Integer> heights) {
        if (heights.isEmpty()) {
            return 64;
        }

        List<Integer> sorted = new ArrayList<>(heights);
        Collections.sort(sorted);

        int size = sorted.size();
        if (size % 2 == 0) {
            return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2;
        } else {
            return sorted.get(size / 2);
        }
    }

    public static int averageHeight(List<Integer> heights) {
        if (heights.isEmpty()) {
            return 64;
        }

        int sum = 0;
        for (int h : heights) {
            sum += h;
        }
        return sum / heights.size();
    }

    public static int modeHeight(List<Integer> heights) {
        if (heights.isEmpty()) {
            return 64;
        }

        java.util.Map<Integer, Integer> frequency = new java.util.HashMap<>();
        for (int h : heights) {
            frequency.put(h, frequency.getOrDefault(h, 0) + 1);
        }

        int maxCount = 0;
        int mode = heights.getFirst();
        for (java.util.Map.Entry<Integer, Integer> entry : frequency.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                mode = entry.getKey();
            }
        }

        return mode;
    }
}
