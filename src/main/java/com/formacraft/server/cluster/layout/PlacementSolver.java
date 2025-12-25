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
        if (units == null || units.isEmpty()) return List.of();
        int gap = Math.max(0, minGap);
        int back = Math.max(0, maxBacktrack);

        List<BuildingUnit> sorted = new ArrayList<>(units);
        sorted.sort(Comparator.comparingInt((BuildingUnit u) -> u.importance).reversed());

        List<BuildingPlacement> placed = new ArrayList<>(sorted.size());
        solveRecursive(sorted, candidatesByUnitId, placed, 0, 0, gap, back);
        return placed;
    }

    private static boolean solveRecursive(List<BuildingUnit> units,
                                          Map<String, List<Candidate>> candidatesByUnitId,
                                          List<BuildingPlacement> placed,
                                          int index,
                                          int backtrackCount,
                                          int gap,
                                          int maxBacktrack) {
        if (index >= units.size()) return true;
        if (backtrackCount > maxBacktrack) return false;

        BuildingUnit unit = units.get(index);
        List<Candidate> list = (unit != null) ? candidatesByUnitId.get(unit.id) : null;
        if (list == null || list.isEmpty()) return false;

        for (Candidate c : list) {
            BuildingPlacement bp = new BuildingPlacement(unit, c.originRel, c.rotation);
            if (conflicts(bp, placed, gap)) continue;
            placed.add(bp);
            if (solveRecursive(units, candidatesByUnitId, placed, index + 1, backtrackCount, gap, maxBacktrack)) return true;
            placed.remove(placed.size() - 1);
        }

        // soft backtrack: try to re-place previous unit if any
        if (!placed.isEmpty()) {
            BuildingPlacement last = placed.remove(placed.size() - 1);
            boolean ok = solveRecursive(units, candidatesByUnitId, placed, index - 1, backtrackCount + 1, gap, maxBacktrack);
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
}


