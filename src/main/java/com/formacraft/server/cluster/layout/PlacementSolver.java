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

        // Prefer candidates that keep the cluster compact around the first placed (main) building,
        // and respect semantic buffers against already-placed key roles (v2: soft constraints).
        List<Candidate> ordered = (cfg != null && !placed.isEmpty())
                ? orderByHeuristics(list, placed, cfg)
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

    private static List<Candidate> orderByHeuristics(List<Candidate> list, List<BuildingPlacement> placed, ClusterLayoutConfig cfg) {
        if (list == null || list.isEmpty() || placed == null || placed.isEmpty() || cfg == null) return list;
        BuildingPlacement main = placed.get(0);
        if (main == null) return list;

        double w = cfg.compactnessWeight;
        double axisW = cfg.axisWeight;
        boolean hasAxis = cfg.axisMode != null && !cfg.axisMode.equals("none") && axisW > 1e-6;
        boolean hasSemantic = cfg.semanticWeightPublic > 1e-6 || cfg.semanticWeightPrivate > 1e-6 || cfg.semanticWeightService > 1e-6
                || cfg.semanticBufferWeight > 1e-6;
        if (w <= 1e-6 && !hasAxis && !hasSemantic) return list;

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
        final double pubTargetN = cfg.semanticPublicTargetDistN;
        final double pubBandN = Math.max(1e-6, cfg.semanticPublicBandN);
        final double privMinN = cfg.semanticPrivateMinDistN;
        final double servMinN = cfg.semanticServiceMinDistN;
        final double wPub = cfg.semanticWeightPublic;
        final double wPriv = cfg.semanticWeightPrivate;
        final double wServ = cfg.semanticWeightService;
        final double bufferMinN = cfg.semanticBufferMinDistN;
        final double bufferW = cfg.semanticBufferWeight;

        // Collect already-placed key role centers (relative).
        final java.util.List<int[]> placedPublic = new java.util.ArrayList<>();
        final java.util.List<int[]> placedService = new java.util.ArrayList<>();
        final java.util.List<int[]> placedPrivate = new java.util.ArrayList<>();
        for (BuildingPlacement bp : placed) {
            if (bp == null || bp.unit == null) continue;
            String r = bp.unit.semanticRole != null ? bp.unit.semanticRole : "";
            int[] pt = new int[]{bp.originRel.getX(), bp.originRel.getZ()};
            if ("PUBLIC".equals(r)) placedPublic.add(pt);
            else if ("SERVICE".equals(r)) placedService.add(pt);
            else if ("PRIVATE".equals(r)) placedPrivate.add(pt);
        }

        // Copy + sort by adjusted score
        List<Candidate> out = new ArrayList<>(list);
        out.sort((a, b) -> {
            double sa = adjustedScore(a, mx, mz, w, maxDistFinal, axisW, axisMode, halfX, halfZ,
                    pubTargetN, pubBandN, privMinN, servMinN, wPub, wPriv, wServ,
                    bufferMinN, bufferW,
                    placedPublic, placedService, placedPrivate);
            double sb = adjustedScore(b, mx, mz, w, maxDistFinal, axisW, axisMode, halfX, halfZ,
                    pubTargetN, pubBandN, privMinN, servMinN, wPub, wPriv, wServ,
                    bufferMinN, bufferW,
                    placedPublic, placedService, placedPrivate);
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
                                        int halfZ,
                                        double pubTargetN,
                                        double pubBandN,
                                        double privMinN,
                                        double servMinN,
                                        double wPub,
                                        double wPriv,
                                        double wServ,
                                        double bufferMinN,
                                        double bufferW,
                                        java.util.List<int[]> placedPublic,
                                        java.util.List<int[]> placedService,
                                        java.util.List<int[]> placedPrivate) {
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

        // Semantic role spacing (optional): by default this is a soft preference.
        // - PUBLIC prefers a distance band away from CORE (not too close, not too far)
        // - PRIVATE/SERVICE prefer being at least minDist away from CORE
        String role = (c.unit != null && c.unit.semanticRole != null) ? c.unit.semanticRole : "";
        if (!role.isEmpty()) {
            switch (role) {
                case "PUBLIC" -> {
                    if (wPub > 1e-6) {
                        double diff = Math.abs(dn - pubTargetN);
                        double pen = Math.min(1.0, diff / pubBandN);
                        s -= (wPub * pen);
                    }
                }
                case "PRIVATE" -> {
                    if (wPriv > 1e-6 && dn < privMinN && privMinN > 1e-6) {
                        double pen = (privMinN - dn) / privMinN;
                        s -= (wPriv * pen);
                    }
                }
                case "SERVICE" -> {
                    if (wServ > 1e-6 && dn < servMinN && servMinN > 1e-6) {
                        double pen = (servMinN - dn) / servMinN;
                        s -= (wServ * pen);
                    }
                }
                default -> {}
            }
        }

        // Semantic buffers between roles (soft):
        // - SERVICE should not be too close to PUBLIC
        // - PRIVATE should not be too close to PUBLIC
        if (bufferW > 1e-6 && bufferMinN > 1e-6) {
            if ("SERVICE".equals(role) && placedPublic != null && !placedPublic.isEmpty()) {
                double dnMin = minDnToPoints(x, z, placedPublic, maxDist);
                if (dnMin < bufferMinN) s -= bufferW * ((bufferMinN - dnMin) / bufferMinN);
            }
            if ("PRIVATE".equals(role) && placedPublic != null && !placedPublic.isEmpty()) {
                double dnMin = minDnToPoints(x, z, placedPublic, maxDist);
                if (dnMin < bufferMinN) s -= bufferW * ((bufferMinN - dnMin) / bufferMinN);
            }
            if ("PUBLIC".equals(role) && placedService != null && !placedService.isEmpty()) {
                double dnMin = minDnToPoints(x, z, placedService, maxDist);
                if (dnMin < bufferMinN) s -= bufferW * ((bufferMinN - dnMin) / bufferMinN);
            }
            if ("PUBLIC".equals(role) && placedPrivate != null && !placedPrivate.isEmpty()) {
                double dnMin = minDnToPoints(x, z, placedPrivate, maxDist);
                if (dnMin < bufferMinN) s -= bufferW * ((bufferMinN - dnMin) / bufferMinN);
            }
        }
        return s;
    }

    private static double minDnToPoints(int x, int z, java.util.List<int[]> pts, double maxDist) {
        double best = 1.0;
        if (pts == null || pts.isEmpty()) return best;
        for (int[] p : pts) {
            if (p == null || p.length < 2) continue;
            double d = Math.sqrt((double) (x - p[0]) * (x - p[0]) + (double) (z - p[1]) * (z - p[1]));
            double dn = Math.min(1.0, d / Math.max(1e-6, maxDist));
            if (dn < best) best = dn;
        }
        return best;
    }
}


