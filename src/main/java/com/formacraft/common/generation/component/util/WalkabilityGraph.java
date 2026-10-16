package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Player standing-node graph over {@link PlannedOccupancy}: solid floor + feet/head clearance,
 * horizontal neighbors and single-block step up/down. No unsupported vertical air flight.
 */
public final class WalkabilityGraph {
    private WalkabilityGraph() {}

    /**
     * Standing posture at floor support {@code (x, floorY, z)}:
     * solid underfoot, clear at floorY+1 (feet) and floorY+2 (head).
     */
    public static boolean canStand(PlannedOccupancy occ, int x, int floorY, int z) {
        if (occ == null) {
            return false;
        }
        return occ.isSupport(x, floorY, z)
                && occ.isBodyClear(x, floorY + 1, z)
                && occ.isBodyClear(x, floorY + 2, z);
    }

    public static boolean canStand(PlannedOccupancy occ, Vec3i floorCell) {
        return floorCell != null && canStand(occ, floorCell.x(), floorCell.y(), floorCell.z());
    }

    /**
     * BFS on floor cells from {@code startFloor} to {@code goalFloor}.
     * Optional {@code bounds} clips search (inclusive min, exclusive max on X/Z/Y for floor Y).
     */
    public static boolean canReach(
            PlannedOccupancy occ,
            Vec3i startFloor,
            Vec3i goalFloor,
            int maxSteps,
            ComponentFootprintUtil.Bounds bounds
    ) {
        if (occ == null || startFloor == null || goalFloor == null) {
            return false;
        }
        if (!canStand(occ, startFloor) || !canStand(occ, goalFloor)) {
            return false;
        }
        if (bounds != null && (!containsFloor(bounds, startFloor) || !containsFloor(bounds, goalFloor))) {
            return false;
        }

        ArrayDeque<int[]> q = new ArrayDeque<>();
        Set<Vec3i> seen = new HashSet<>();
        q.add(new int[]{startFloor.x(), startFloor.y(), startFloor.z(), 0});
        seen.add(startFloor);

        int[][] horiz = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty()) {
            int[] cur = q.removeFirst();
            int x = cur[0], fy = cur[1], z = cur[2], dist = cur[3];
            if (x == goalFloor.x() && fy == goalFloor.y() && z == goalFloor.z()) {
                return true;
            }
            if (dist >= maxSteps) {
                continue;
            }
            for (int[] h : horiz) {
                int nx = x + h[0];
                int nz = z + h[1];
                // Same level
                tryEnqueue(occ, bounds, seen, q, nx, fy, nz, dist + 1);
                // Step up / down one block
                tryEnqueue(occ, bounds, seen, q, nx, fy + 1, nz, dist + 1);
                tryEnqueue(occ, bounds, seen, q, nx, fy - 1, nz, dist + 1);
            }
        }
        return false;
    }

    public static boolean canReach(PlannedOccupancy occ, Vec3i startFloor, Vec3i goalFloor, int maxSteps) {
        return canReach(occ, startFloor, goalFloor, maxSteps, null);
    }

    private static void tryEnqueue(
            PlannedOccupancy occ,
            ComponentFootprintUtil.Bounds bounds,
            Set<Vec3i> seen,
            ArrayDeque<int[]> q,
            int x,
            int floorY,
            int z,
            int dist
    ) {
        Vec3i key = new Vec3i(x, floorY, z);
        if (bounds != null && !containsFloor(bounds, key)) {
            return;
        }
        if (!canStand(occ, x, floorY, z)) {
            return;
        }
        if (!seen.add(key)) {
            return;
        }
        q.addLast(new int[]{x, floorY, z, dist});
    }

    private static boolean containsFloor(ComponentFootprintUtil.Bounds bounds, Vec3i floor) {
        if (bounds == null || floor == null) {
            return true;
        }
        return floor.x() >= bounds.minX() && floor.x() < bounds.maxX()
                && floor.z() >= bounds.minZ() && floor.z() < bounds.maxZ()
                && floor.y() >= bounds.minY() && floor.y() < bounds.maxY();
    }

    /** Union of component AABBs shifted by offsets, then expand horizontally by {@code margin}. */
    public static ComponentFootprintUtil.Bounds searchBounds(
            ComponentFootprintUtil.Bounds a,
            Vec3i aOff,
            ComponentFootprintUtil.Bounds b,
            Vec3i bOff,
            ComponentFootprintUtil.Bounds c,
            Vec3i cOff,
            int margin
    ) {
        ComponentFootprintUtil.Bounds u = null;
        u = unionShifted(u, a, aOff);
        u = unionShifted(u, b, bOff);
        u = unionShifted(u, c, cOff);
        if (u == null) {
            return null;
        }
        // Allow step Y outside mass height by 2 for headroom / step edges.
        ComponentFootprintUtil.Bounds padded = new ComponentFootprintUtil.Bounds(
                u.minX(), u.minY() - 1, u.minZ(),
                u.maxX(), u.maxY() + 3, u.maxZ());
        return padded.expandHorizontal(Math.max(0, margin));
    }

    private static ComponentFootprintUtil.Bounds unionShifted(
            ComponentFootprintUtil.Bounds acc,
            ComponentFootprintUtil.Bounds local,
            Vec3i off
    ) {
        if (local == null) {
            return acc;
        }
        int ox = off == null ? 0 : off.x();
        int oy = off == null ? 0 : off.y();
        int oz = off == null ? 0 : off.z();
        ComponentFootprintUtil.Bounds shifted = new ComponentFootprintUtil.Bounds(
                local.minX() + ox, local.minY() + oy, local.minZ() + oz,
                local.maxX() + ox, local.maxY() + oy, local.maxZ() + oz);
        return acc == null ? shifted : acc.union(shifted);
    }
}
