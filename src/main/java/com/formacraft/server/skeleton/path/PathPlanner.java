package com.formacraft.server.skeleton.path;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Very small path planner helper (v1):
 * - Works in RELATIVE coordinates (to a generator origin)
 * - Produces an orthogonal polyline (L-shape) between two points on XZ.
 *
 * This is intentionally simple and deterministic; future versions can add obstacle avoidance.
 */
public final class PathPlanner {
    private PathPlanner() {}

    public static List<BlockPos> orthogonalL(BlockPos startRel, BlockPos endRel) {
        List<BlockPos> pts = new ArrayList<>(3);
        if (startRel == null || endRel == null) return pts;
        pts.add(startRel);

        int sx = startRel.getX();
        int sy = startRel.getY();
        int sz = startRel.getZ();
        int ex = endRel.getX();
        int ez = endRel.getZ();

        if (sx == ex || sz == ez) {
            pts.add(endRel);
            return pts;
        }

        // X then Z (stable + symmetric enough for courtyards/grids)
        pts.add(new BlockPos(ex, sy, sz));
        pts.add(endRel);
        return pts;
    }
}


