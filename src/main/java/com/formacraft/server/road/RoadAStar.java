package com.formacraft.server.road;

import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * RoadAStar: 4-neighbor A* on XZ with Y sampled from terrain (RoadSurfaceAnalyzer).
 *
 * Notes:
 * - Node positions are full BlockPos with sampled Y.
 * - Uses Manhattan heuristic on XZ.
 */
public final class RoadAStar {
    private RoadAStar() {}

    private static final int[][] DIRS = new int[][]{
            {1, 0},
            {-1, 0},
            {0, 1},
            {0, -1}
    };

    public static List<BlockPos> findPath(BlockPos start,
                                          BlockPos goal,
                                          RoadSurfaceAnalyzer analyzer,
                                          int maxSearch) {
        if (start == null || goal == null || analyzer == null) return List.of();
        int budget = Math.max(500, maxSearch);

        RoadSurfaceAnalyzer.SurfaceInfo s0 = analyzer.sample(start.getX(), start.getZ());
        RoadSurfaceAnalyzer.SurfaceInfo g0 = analyzer.sample(goal.getX(), goal.getZ());
        if (s0.surface() == RoadSurface.BLOCKED || g0.surface() == RoadSurface.BLOCKED) return List.of();

        BlockPos startPos = s0.pos();
        BlockPos goalPos = g0.pos();

        PriorityQueue<RoadNode> open = new PriorityQueue<>(Comparator.comparingInt(RoadNode::fCost));
        Map<Long, Integer> best = new HashMap<>(budget * 2);

        open.add(new RoadNode(startPos, 0, heuristic(startPos, goalPos), null));
        best.put(keyXZ(startPos), 0);

        int expansions = 0;
        while (!open.isEmpty() && expansions < budget) {
            RoadNode cur = open.poll();
            expansions++;
            if (sameXZ(cur.pos, goalPos)) return reconstruct(cur);

            for (int[] d : DIRS) {
                int nx = cur.pos.getX() + d[0];
                int nz = cur.pos.getZ() + d[1];
                RoadSurfaceAnalyzer.SurfaceInfo ni = analyzer.sample(nx, nz);
                if (ni.surface() == RoadSurface.BLOCKED) continue;

                BlockPos next = ni.pos();
                int dy = next.getY() - cur.pos.getY();
                if (Math.abs(dy) > analyzer.maxStep()) continue;

                int move = moveCost(ni.surface(), dy);
                int ng = cur.gCost + move;

                long k = keyXZ(next);
                Integer old = best.get(k);
                if (old != null && old <= ng) continue;
                best.put(k, ng);

                open.add(new RoadNode(next, ng, heuristic(next, goalPos), cur));
            }
        }

        return List.of();
    }

    private static boolean sameXZ(BlockPos a, BlockPos b) {
        return a.getX() == b.getX() && a.getZ() == b.getZ();
    }

    private static int heuristic(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ());
    }

    private static int moveCost(RoadSurface surface, int dy) {
        int base = 1;
        if (surface == RoadSurface.BRIDGE) base += 6;
        if (Math.abs(dy) == 1) base += 3;
        return base;
    }

    private static List<BlockPos> reconstruct(RoadNode node) {
        ArrayList<BlockPos> path = new ArrayList<>();
        RoadNode n = node;
        while (n != null) {
            path.add(n.pos);
            n = n.parent;
        }
        Collections.reverse(path);
        return path;
    }

    private static long keyXZ(BlockPos p) {
        // pack x/z into long (signed 26-ish bits is enough for typical play; collisions acceptable for A* pruning)
        return (((long) p.getX()) << 32) ^ (p.getZ() & 0xffffffffL);
    }
}


