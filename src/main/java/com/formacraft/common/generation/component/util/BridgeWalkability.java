package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * BlockPatch-map walkability for bridge decks: missing/air is free; solid blocks block;
 * standing on spruce (or any non-air) at feet-1 is allowed when the head cell is free.
 */
public final class BridgeWalkability {
    private BridgeWalkability() {}

    public static boolean isPassable(Map<Vec3i, String> blocks, int x, int y, int z) {
        String id = blocks.get(new Vec3i(x, y, z));
        return id == null || isAir(id);
    }

    public static boolean isAir(String blockId) {
        if (blockId == null) {
            return true;
        }
        String lower = blockId.toLowerCase(Locale.ROOT);
        return lower.equals("minecraft:air") || lower.endsWith(":air") || lower.contains("air");
    }

    /** 6-connected BFS from standing cell start to goal (both at head/torso height). */
    public static boolean canReach(Map<Vec3i, String> blocks, Vec3i start, Vec3i goal, int maxSteps) {
        if (blocks == null || start == null || goal == null) {
            return false;
        }
        if (!isPassable(blocks, start.x(), start.y(), start.z())
                || !isPassable(blocks, goal.x(), goal.y(), goal.z())) {
            return false;
        }
        ArrayDeque<long[]> q = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        long sKey = pack(start.x(), start.y(), start.z());
        q.add(new long[]{start.x(), start.y(), start.z(), 0});
        seen.add(sKey);
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};
        while (!q.isEmpty()) {
            long[] cur = q.removeFirst();
            int x = (int) cur[0], y = (int) cur[1], z = (int) cur[2], dist = (int) cur[3];
            if (x == goal.x() && y == goal.y() && z == goal.z()) {
                return true;
            }
            if (dist >= maxSteps) {
                continue;
            }
            for (int[] d : dirs) {
                int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                if (!isPassable(blocks, nx, ny, nz)) {
                    continue;
                }
                long key = pack(nx, ny, nz);
                if (!seen.add(key)) {
                    continue;
                }
                q.addLast(new long[]{nx, ny, nz, dist + 1});
            }
        }
        return false;
    }

    /** Interior standing cell of a mass at plateY+1, inset by wall_thickness. */
    public static Vec3i interiorStandingCell(Component mass, int plateY) {
        var bounds = ComponentFootprintUtil.bounds(mass);
        if (bounds == null) {
            return null;
        }
        int thickness = Math.max(1, ComponentParamParsers.intParam(
                mass.params(), 1, "wall_thickness", "wallThickness"));
        int x = bounds.minX() + thickness;
        int z = bounds.minZ() + Math.max(thickness, bounds.depth() / 2);
        if (x >= bounds.maxX() - thickness) {
            x = (bounds.minX() + bounds.maxX()) / 2;
        }
        if (z >= bounds.maxZ() - thickness) {
            z = (bounds.minZ() + bounds.maxZ()) / 2;
        }
        return new Vec3i(x, plateY + 1, z);
    }

    public static Vec3i deckStandingCell(Component plate) {
        var bounds = ComponentFootprintUtil.bounds(plate);
        if (bounds == null || plate.relativePosition() == null) {
            return null;
        }
        int x = (bounds.minX() + bounds.maxX()) / 2;
        int z = (bounds.minZ() + bounds.maxZ()) / 2;
        return new Vec3i(x, plate.relativePosition().y() + 1, z);
    }

    public static boolean bridgeConnectsInteriors(
            Map<Vec3i, String> blocks,
            Component plate,
            Component host,
            Component target
    ) {
        return bridgeConnectsInteriors(blocks, plate, ZERO, host, ZERO, target, ZERO);
    }

    /**
     * Same as {@link #bridgeConnectsInteriors(Map, Component, Component, Component)} but standing
     * cells are shifted by each component's slot anchor (compile output space).
     */
    public static boolean bridgeConnectsInteriors(
            Map<Vec3i, String> blocks,
            Component plate,
            Vec3i plateOffset,
            Component host,
            Vec3i hostOffset,
            Component target,
            Vec3i targetOffset
    ) {
        if (plate == null || plate.relativePosition() == null) {
            return false;
        }
        int plateY = plate.relativePosition().y();
        Vec3i deck = shift(deckStandingCell(plate), plateOffset);
        Vec3i hostIn = shift(interiorStandingCell(host, plateY), hostOffset);
        Vec3i targetIn = shift(interiorStandingCell(target, plateY), targetOffset);
        if (deck == null || hostIn == null || targetIn == null) {
            return false;
        }
        int budget = 512;
        return canReach(blocks, deck, hostIn, budget) && canReach(blocks, deck, targetIn, budget);
    }

    public static Map<Vec3i, String> blocksFromPatches(Iterable<com.formacraft.common.patch.BlockPatch> patches) {
        Map<Vec3i, String> blocks = new java.util.HashMap<>();
        if (patches == null) {
            return blocks;
        }
        for (com.formacraft.common.patch.BlockPatch patch : patches) {
            if (patch == null) {
                continue;
            }
            Vec3i pos = new Vec3i(patch.dx(), patch.dy(), patch.dz());
            if (com.formacraft.common.patch.BlockPatch.REMOVE.equals(patch.action())
                    || "minecraft:air".equals(patch.targetBlock())) {
                blocks.remove(pos);
            } else {
                blocks.put(pos, patch.targetBlock());
            }
        }
        return blocks;
    }

    private static final Vec3i ZERO = new Vec3i(0, 0, 0);

    private static Vec3i shift(Vec3i local, Vec3i offset) {
        if (local == null) {
            return null;
        }
        if (offset == null) {
            return local;
        }
        return new Vec3i(local.x() + offset.x(), local.y() + offset.y(), local.z() + offset.z());
    }

    private static long pack(int x, int y, int z) {
        return (((long) (x + 512) & 0x3FFL) << 20)
                | (((long) (y + 512) & 0x3FFL) << 10)
                | ((long) (z + 512) & 0x3FFL);
    }
}
