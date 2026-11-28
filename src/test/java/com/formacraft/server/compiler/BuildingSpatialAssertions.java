package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Spatial invariants for junction / void regression (Phase 02.3.4 Task G).
 * Walkability uses real solid/air heuristics — not {@code !stone_brick}.
 */
public final class BuildingSpatialAssertions {
    private BuildingSpatialAssertions() {}

    public static Map<Vec3i, String> index(List<BlockPatch> patches) {
        Map<Vec3i, String> out = new HashMap<>();
        if (patches == null) return out;
        for (BlockPatch p : patches) {
            if (p == null) continue;
            Vec3i k = new Vec3i(p.dx(), p.dy(), p.dz());
            if (BlockPatch.REMOVE.equals(p.action())) {
                out.remove(k);
            } else if (BlockPatch.PLACE.equals(p.action())) {
                out.put(k, p.targetBlock() == null ? "" : p.targetBlock());
            }
        }
        return out;
    }

    public static boolean isAir(Map<Vec3i, String> blocks, int x, int y, int z) {
        String b = blocks.get(new Vec3i(x, y, z));
        return b == null || b.isBlank() || b.toLowerCase(Locale.ROOT).contains("air");
    }

    public static boolean isSolid(Map<Vec3i, String> blocks, int x, int y, int z) {
        String b = blocks.get(new Vec3i(x, y, z));
        if (b == null || b.isBlank()) return false;
        String s = b.toLowerCase(Locale.ROOT);
        if (s.contains("air")) return false;
        // Non-full collision / pass-through fixtures.
        if (s.contains("torch") || s.contains("lantern") || s.contains("button")
                || s.contains("rail") || s.contains("sign") || s.contains("carpet")
                || s.contains("pressure_plate") || s.contains("flower") || s.contains("sapling")
                || s.contains("grass") && !s.contains("grass_block")) {
            return false;
        }
        return true;
    }

    /** Player-pass cell: feet air + head air + solid support under feet. */
    public static boolean isWalkable(Map<Vec3i, String> blocks, int x, int y, int z) {
        if (!isAir(blocks, x, y, z)) return false;
        if (!isAir(blocks, x, y + 1, z)) return false;
        return isSolid(blocks, x, y - 1, z);
    }

    public static boolean voidPreserved(Map<Vec3i, String> blocks, int x, int y, int z) {
        return isAir(blocks, x, y, z);
    }

    public static boolean occupied(Map<Vec3i, String> blocks, int x, int y, int z) {
        return isSolid(blocks, x, y, z);
    }

    public static void requireNonEmpty(List<BlockPatch> patches) {
        if (patches == null || patches.isEmpty()) {
            throw new AssertionError("expected non-empty BlockPatch list");
        }
        boolean anyPlace = false;
        for (BlockPatch p : patches) {
            if (p != null && BlockPatch.PLACE.equals(p.action())) {
                anyPlace = true;
                break;
            }
        }
        if (!anyPlace) throw new AssertionError("expected at least one PLACE patch");
    }

    /** 2-high air column (topology / player body), without floor-support requirement. */
    public static boolean isPassableColumn(Map<Vec3i, String> blocks, int x, int y, int z) {
        return isAir(blocks, x, y, z) && isAir(blocks, x, y + 1, z);
    }

    /**
     * BFS on walkable cells (2-high air + support). Distinct from {@link #reachablePassable}.
     */
    public static boolean reachableWalkable(
            Map<Vec3i, String> blocks,
            Vec3i start,
            Vec3i goal,
            int x0, int x1, int y0, int y1, int z0, int z1
    ) {
        return reachable(blocks, start, goal, x0, x1, y0, y1, z0, z1, true);
    }

    /**
     * Topology connectivity through 2-high air (not {@code !stone_brick}). Player-pass
     * with support uses {@link #reachableWalkable}.
     */
    public static boolean reachablePassable(
            Map<Vec3i, String> blocks,
            Vec3i start,
            Vec3i goal,
            int x0, int x1, int y0, int y1, int z0, int z1
    ) {
        return reachable(blocks, start, goal, x0, x1, y0, y1, z0, z1, false);
    }

    private static boolean reachable(
            Map<Vec3i, String> blocks,
            Vec3i start,
            Vec3i goal,
            int x0, int x1, int y0, int y1, int z0, int z1,
            boolean requireSupport
    ) {
        Objects.requireNonNull(start);
        Objects.requireNonNull(goal);
        boolean startOk = requireSupport
                ? isWalkable(blocks, start.x(), start.y(), start.z())
                : isPassableColumn(blocks, start.x(), start.y(), start.z());
        if (!startOk && !(start.equals(goal) && isAir(blocks, start.x(), start.y(), start.z()))) {
            return false;
        }
        Set<Long> seen = new HashSet<>();
        ArrayDeque<Vec3i> q = new ArrayDeque<>();
        q.add(start);
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!q.isEmpty()) {
            Vec3i c = q.removeFirst();
            if (c.equals(goal)) return true;
            long key = (((long) c.x()) << 40) | (((long) c.y()) << 20) | (c.z() & 0xFFFFF);
            if (!seen.add(key)) continue;
            for (int[] n : dirs) {
                int nx = c.x() + n[0], ny = c.y() + n[1], nz = c.z() + n[2];
                if (nx < x0 || nx >= x1 || ny < y0 || ny >= y1 || nz < z0 || nz >= z1) continue;
                if (nx == goal.x() && ny == goal.y() && nz == goal.z()) {
                    q.add(new Vec3i(nx, ny, nz));
                    continue;
                }
                boolean ok = requireSupport
                        ? isWalkable(blocks, nx, ny, nz)
                        : isPassableColumn(blocks, nx, ny, nz);
                if (!ok) continue;
                q.add(new Vec3i(nx, ny, nz));
            }
        }
        return false;
    }

    public static boolean deterministic(List<BlockPatch> a, List<BlockPatch> b) {
        return Objects.equals(index(a), index(b));
    }
}
