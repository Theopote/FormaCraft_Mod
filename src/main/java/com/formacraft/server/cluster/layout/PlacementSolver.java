package com.formacraft.server.cluster.layout;

import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * PlacementSolver: greedy with soft backtracking (v1).
 */
public final class PlacementSolver {
    private PlacementSolver() {}

    public static List<BuildingPlacement> solve(List<BuildingUnit> units,
                                                Map<String, List<Candidate>> candidatesByUnitId,
                                                int minGap,
                                                int maxBacktrack) {
        return solve(units, candidatesByUnitId, minGap, maxBacktrack, null);
    }

    public static List<BuildingPlacement> solve(List<BuildingUnit> units,
                                                Map<String, List<Candidate>> candidatesByUnitId,
                                                int minGap,
                                                int maxBacktrack,
                                                ClusterLayoutConfig cfg) {
        if (units == null || units.isEmpty()) return List.of();
        int gap = Math.max(0, minGap);
        int back = Math.max(0, maxBacktrack);

        List<BuildingUnit> sorted = new ArrayList<>(units);
        sorted.sort(Comparator.comparingInt((BuildingUnit u) -> u.importance).reversed());

        List<BuildingPlacement> placed = new ArrayList<>(sorted.size());
        solveRecursive(sorted, candidatesByUnitId, placed, 0, 0, gap, back, cfg);
        return placed;
    }

    private static boolean solveRecursive(List<BuildingUnit> units,
                                          Map<String, List<Candidate>> candidatesByUnitId,
                                          List<BuildingPlacement> placed,
                                          int index,
                                          int backtrackCount,
                                          int gap,
                                          int maxBacktrack,
                                          ClusterLayoutConfig cfg) {
        if (index >= units.size()) return true;
        if (backtrackCount > maxBacktrack) return false;

        BuildingUnit unit = units.get(index);
        List<Candidate> list = (unit != null) ? candidatesByUnitId.get(unit.id) : null;
        if (list == null || list.isEmpty()) return false;

        // Prefer candidates that keep the cluster compact around the first placed (main) building.
        List<Candidate> ordered = (cfg != null && cfg.compactnessWeight > 1e-6 && !placed.isEmpty())
                ? orderByCompactness(list, placed.get(0), cfg)
                : list;

        for (Candidate c : ordered) {
            BuildingPlacement bp = new BuildingPlacement(unit, c.originRel, c.rotation);
            if (conflicts(bp, placed, gap)) continue;
            placed.add(bp);
            if (solveRecursive(units, candidatesByUnitId, placed, index + 1, backtrackCount, gap, maxBacktrack, cfg)) return true;
            placed.remove(placed.size() - 1);
        }

        // soft backtrack: try to re-place previous unit if any
        if (!placed.isEmpty()) {
            BuildingPlacement last = placed.remove(placed.size() - 1);
            boolean ok = solveRecursive(units, candidatesByUnitId, placed, index - 1, backtrackCount + 1, gap, maxBacktrack, cfg);
            if (ok) return true;
            // restore (best-effort)
            placed.add(last);
        }
        return false;
    }

    private static boolean conflicts(BuildingPlacement bp, List<BuildingPlacement> placed, int gap) {
        Box box = bp.getBox();
        if (gap > 0) box = box.expand(gap, 0, gap);
        for (BuildingPlacement other : placed) {
            if (box.intersects(other.getBox())) return true;
        }
        return false;
    }

    private static List<Candidate> orderByCompactness(List<Candidate> list, BuildingPlacement main, ClusterLayoutConfig cfg) {
        if (list == null || list.isEmpty() || main == null || cfg == null) return list;
        double w = cfg.compactnessWeight;
        double axisW = cfg.axisWeight;
        boolean hasAxis = cfg.axisMode != null && !cfg.axisMode.equals("none") && axisW > 1e-6;
        if (w <= 1e-6 && !hasAxis) return list;

        // Normalize distance by a configurable maxDist; auto uses half box diagonal.
        final double maxDistFinal;
        double md = cfg.compactnessMaxDist;
        if (md <= 0.0) {
            double dx = cfg.halfX;
            double dz = cfg.halfZ;
            md = Math.max(1.0, Math.sqrt(dx * dx + dz * dz));
        }
        maxDistFinal = md;

        int mx = main.originRel.getX();
        int mz = main.originRel.getZ();

        final int halfX = cfg.halfX;
        final int halfZ = cfg.halfZ;
        final String axisMode = cfg.axisMode;

        // Copy + sort by adjusted score
        List<Candidate> out = new ArrayList<>(list);
        out.sort((a, b) -> {
            double sa = adjustedScore(a, mx, mz, w, maxDistFinal, axisW, axisMode, halfX, halfZ);
            double sb = adjustedScore(b, mx, mz, w, maxDistFinal, axisW, axisMode, halfX, halfZ);
            return Double.compare(sb, sa);
        });
        return out;
    }

    private static double adjustedScore(Candidate c,
                                        int mx,
                                        int mz,
                                        double compactW,
                                        double maxDist,
                                        double axisW,
                                        String axisMode,
                                        int halfX,
                                        int halfZ) {
        if (c == null) return -1e9;
        int x = c.originRel.getX();
        int z = c.originRel.getZ();
        double d = Math.sqrt((double) (x - mx) * (x - mx) + (double) (z - mz) * (z - mz));
        double dn = Math.min(1.0, d / Math.max(1e-6, maxDist)); // 0..1
        double s = c.score;
        // Penalize being far from main.
        if (compactW > 1e-6) s -= (compactW * dn);

        // Penalize being far from main axis line (optional).
        if (axisW > 1e-6 && axisMode != null && !axisMode.equals("none")) {
            double an;
            if (axisMode.equals("x")) {
                // axis line along X through main => penalize Z offset
                an = Math.min(1.0, Math.abs(z - mz) / (double) Math.max(1, halfZ));
            } else if (axisMode.equals("z")) {
                // axis line along Z through main => penalize X offset
                an = Math.min(1.0, Math.abs(x - mx) / (double) Math.max(1, halfX));
            } else {
                an = 0.0;
            }
            s -= (axisW * an);
        }
        return s;
    }
}


