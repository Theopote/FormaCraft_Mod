package com.formacraft.server.cluster;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * ClusterLayoutPlanner (v0):
 * Minimal, terrain-friendly layout for placing repeated building units on rough terrain.
 *
 * Design points absorbed from the suggestion:
 * - scoring + search (deterministic candidates + greedy selection)
 * - avoid "整片平整" by preferring low local variation/cost, not by flattening everything
 * - spacing constraint to avoid "撒芝麻"
 *
 * v0 scope:
 * - planar placements (dx,dz). Y is handled by TerrainPolicy=ADAPTIVE (snap + pad).
 * - supports only 0-rotation for now.
 */
public final class ClusterLayoutPlanner {
    private ClusterLayoutPlanner() {}

    public record Placement(int dx, int dz, double score, Metrics metrics) {}

    public record Metrics(int minY, int maxY, int avgY, int range, int flattenCost, float slopeAvg, int buildableBad, int waterHits) {}

    /**
     * Plan placements around origin within a bounding box.
     *
     * @param count number of units to place
     * @param boxHalfX half width of layout region in X
     * @param boxHalfZ half width of layout region in Z
     * @param minGap minimum spacing between unit centers (in blocks)
     * @param footprintW unit footprint width
     * @param footprintD unit footprint depth
     * @param candidateBudget number of random candidates to evaluate (higher = better, slower)
     */
    public static List<Placement> plan(ServerWorld world,
                                       BlockPos origin,
                                       int count,
                                       int boxHalfX,
                                       int boxHalfZ,
                                       int minGap,
                                       int footprintW,
                                       int footprintD,
                                       int candidateBudget) {
        if (world == null || origin == null || count <= 0) return List.of();

        int halfX = Math.max(8, boxHalfX);
        int halfZ = Math.max(8, boxHalfZ);
        int gap = Math.max(2, minGap);
        int w = Math.max(5, footprintW);
        int d = Math.max(5, footprintD);
        int budget = Math.max(count * 40, candidateBudget);

        Random rng = new Random(seedFrom(origin));

        TerrainFields fields = TerrainFields.sample(world, origin, halfX, halfZ, 2);

        // Pre-generate candidate set
        List<Placement> candidates = new ArrayList<>(budget);
        for (int i = 0; i < budget; i++) {
            int dx = rng.nextInt(halfX * 2 + 1) - halfX;
            int dz = rng.nextInt(halfZ * 2 + 1) - halfZ;

            int cx = origin.getX() + dx;
            int cz = origin.getZ() + dz;
            TerrainFields.FootprintMetrics fm = fields.footprintMetrics(world, cx, cz, w, d);
            Metrics m = new Metrics(fm.minY(), fm.maxY(), fm.avgY(), fm.range(), fm.flattenCost(), fm.slopeAvg(), fm.buildableBad(), fm.waterHits());

            // Hard filters (fast):
            // - avoid heavy constraint violations
            if (m.buildableBad > 0) continue;
            // - avoid water by default
            if (m.waterHits > 0) continue;
            // - avoid extreme cliffs for footprints (moderate terrain can be handled by footing plan)
            if (m.range > 14) continue;

            double score = scoreCandidate(dx, dz, m, halfX, halfZ);
            candidates.add(new Placement(dx, dz, score, m));
        }

        // Greedy placement with spacing constraint: place best remaining.
        candidates.sort(Comparator.comparingDouble((Placement p) -> p.score).reversed());

        List<Placement> chosen = solveGreedyWithBacktrack(candidates, count, gap);

        // If we failed (very tight region), relax spacing a bit and try again once.
        if (chosen.size() < count && gap > 2) {
            int gap2 = Math.max(2, (int) Math.floor(gap * 0.8));
            chosen = solveGreedyWithBacktrack(candidates, count, gap2);
        }

        return chosen;
    }

    private static boolean isFarEnough(List<Placement> chosen, Placement c, int minGap) {
        int g2 = minGap * minGap;
        for (Placement p : chosen) {
            int dx = p.dx - c.dx;
            int dz = p.dz - c.dz;
            if (dx * dx + dz * dz < g2) return false;
        }
        return true;
    }

    private static List<Placement> solveGreedyWithBacktrack(List<Placement> sortedCandidates, int count, int gap) {
        // 1-step soft backtracking:
        // - choose best for i
        // - if later i+1 cannot be placed, rollback i and try next-best for i (once)
        int n = sortedCandidates.size();
        if (n == 0) return List.of();

        List<Placement> chosen = new ArrayList<>(count);
        int idx = 0;
        int backtrackTries = 0;
        while (idx < n && chosen.size() < count) {
            Placement cand = sortedCandidates.get(idx);
            if (isFarEnough(chosen, cand, gap)) {
                chosen.add(cand);

                // quick lookahead: ensure there exists at least one remaining candidate for the next slot
                if (chosen.size() < count && !existsAnyFeasible(sortedCandidates, idx + 1, chosen, gap)) {
                    // backtrack once
                    chosen.remove(chosen.size() - 1);
                    backtrackTries++;
                    if (backtrackTries > 1) {
                        // don't loop forever; accept partial result
                        return chosen;
                    }
                    // try next candidate instead
                } else {
                    backtrackTries = 0;
                }
            }
            idx++;
        }
        return chosen;
    }

    private static boolean existsAnyFeasible(List<Placement> candidates, int startIdx, List<Placement> chosen, int gap) {
        int limit = Math.min(candidates.size(), startIdx + 80); // bounded scan
        for (int i = startIdx; i < limit; i++) {
            if (isFarEnough(chosen, candidates.get(i), gap)) return true;
        }
        return false;
    }

    private static double scoreCandidate(int dx, int dz, Metrics m, int halfX, int halfZ) {
        // Terrain: prefer low range and low flatten cost
        double rangeScore = 1.0 / (1.0 + m.range);
        double costScore = 1.0 / (1.0 + (m.flattenCost / 20.0));
        double slopeScore = 1.0 / (1.0 + (m.slopeAvg * 4.0)); // gentle slopes

        // Keep within box, prefer closer to center a bit (office district), but not too strong.
        double nx = Math.abs(dx) / (double) Math.max(1, halfX);
        double nz = Math.abs(dz) / (double) Math.max(1, halfZ);
        double centerScore = 1.0 - 0.6 * (nx * nx + nz * nz) / 2.0;

        // Weighted sum (simple, stable)
        return 0.35 * rangeScore + 0.30 * costScore + 0.20 * slopeScore + 0.15 * centerScore;
    }

    private static long seedFrom(BlockPos origin) {
        long x = origin.getX();
        long z = origin.getZ();
        long y = origin.getY();
        long s = x * 341873128712L + z * 132897987541L + y * 17L;
        return s ^ (s >>> 33);
    }

    public static boolean isClusterMode(Object v) {
        if (v == null) return false;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return s.equals("cluster") || s.equals("terrain_cluster") || s.equals("adaptive_cluster");
    }
}


