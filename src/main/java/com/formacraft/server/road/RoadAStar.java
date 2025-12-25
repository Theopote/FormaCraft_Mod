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
        return findPath(start, goal, analyzer, maxSearch, 12, 2, 6);
    }

    /**
     * @param stepPenalty extra cost for dy=±1 moves (higher => prefer detours to keep flatter grade)
     * @param localSlopePenalty cost per localSlope unit (higher => avoid rugged/steep areas)
     */
    public static List<BlockPos> findPath(BlockPos start,
                                          BlockPos goal,
                                          RoadSurfaceAnalyzer analyzer,
                                          int maxSearch,
                                          int stepPenalty,
                                          int localSlopePenalty,
                                          int bridgePenalty) {
        if (start == null || goal == null || analyzer == null) return List.of();
        int budget = Math.max(500, maxSearch);
        int stepP = Math.max(0, stepPenalty);
        int slopeP = Math.max(0, localSlopePenalty);
        int bridgeP = Math.max(0, bridgePenalty);

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

                int move = moveCost(ni.surface(), dy, ni.localSlope(), stepP, slopeP, bridgeP);
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

    private static int moveCost(RoadSurface surface, int dy, int localSlope, int stepPenalty, int slopePenalty, int bridgePenalty) {
        int base = 1;
        if (surface == RoadSurface.BRIDGE) base += bridgePenalty;
        if (Math.abs(dy) == 1) base += stepPenalty;
        if (slopePenalty > 0 && localSlope > 0) base += (localSlope * slopePenalty);
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


