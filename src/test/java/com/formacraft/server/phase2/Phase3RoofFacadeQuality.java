package com.formacraft.server.phase2;

import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Phase 3：屋顶覆盖与立面开口质量启发式（基于编译后最终方块集，不依赖观感人工打分）。
 */
public final class Phase3RoofFacadeQuality {
    private Phase3RoofFacadeQuality() {}

    public record RoofReport(
            int footprintColumns,
            int coveredColumns,
            double coverageRatio,
            int roofRiseSpan,
            int ridgeOrPeakCells,
            boolean hasPitchedProfile,
            int shapedRoofCells
    ) {}

    public record FacadeReport(
            int southOpenings,
            int northOpenings,
            int eastOpenings,
            int westOpenings,
            int totalOpenings,
            int openingDistinctYs,
            double southRhythmScore,
            /** 0..1 share of SOUTH-face span that is solid pier between first/last opening. */
            double southPierGapRatio
    ) {}

    public record Report(RoofReport roof, FacadeReport facade, List<String> issues) {}

    public static Report analyze(List<BlockPatch> patches, Set<String> focus) {
        return analyze(patches, focus, false, false);
    }

    public static Report analyze(
            List<BlockPatch> patches,
            Set<String> focus,
            boolean requireRoofLike,
            boolean requireOpeningLike
    ) {
        Map<BlockPos, String> cells = Phase2StructuralChecks.collapseFinalPlaceCells(patches);
        RoofReport roof = analyzeRoof(cells, patches);
        FacadeReport facade = analyzeFacade(cells);
        List<String> issues = new ArrayList<>();
        Set<String> f = focus == null ? Set.of() : focus;

        boolean checkRoof = requireRoofLike
                || f.contains("roof")
                || f.contains("complex_roof")
                || f.contains("multi_eave");
        boolean flatRoofOk = f.contains("massing")
                || f.contains("window_rhythm")
                || f.contains("floors")
                || f.contains("glass_facade");
        boolean wantsPitched = !flatRoofOk && (
                f.contains("complex_roof")
                        || f.contains("multi_eave")
                        || f.contains("roof")
                        || requireRoofLike && !f.contains("repetition"));

        if (checkRoof) {
            // Multi-eave / 重檐 volumes leave terraces under the upper band; allow a softer floor.
            double minCoverage = f.contains("multi_eave") ? 0.40 : 0.55;
            if (roof.coverageRatio() < minCoverage) {
                issues.add(String.format(Locale.ROOT,
                        "roof_coverage=%.2f < %.2f (covered=%d/%d)",
                        roof.coverageRatio(), minCoverage,
                        roof.coveredColumns(), roof.footprintColumns()));
            }
            if (wantsPitched && !roof.hasPitchedProfile() && roof.roofRiseSpan() < 2) {
                issues.add("expected pitched roof profile, riseSpan=" + roof.roofRiseSpan()
                        + " pitched=" + roof.hasPitchedProfile());
            }
            if ((f.contains("complex_roof") || f.contains("multi_eave") || f.contains("roof"))
                    && !flatRoofOk
                    && roof.shapedRoofCells() < 8) {
                issues.add("shapedRoofCells=" + roof.shapedRoofCells()
                        + " < 8 (pitched roofs should express stairs/slabs)");
            }
        }

        boolean checkOpenings = requireOpeningLike
                || f.contains("openings")
                || f.contains("glass_facade")
                || f.contains("window_rhythm")
                || f.contains("complex_facade")
                || f.contains("repetition");
        if (checkOpenings && facade.totalOpenings() < 2) {
            issues.add("facade openings=" + facade.totalOpenings() + " < 2");
        }
        if (f.contains("glass_facade") && facade.totalOpenings() < 6) {
            issues.add("glass_facade openings=" + facade.totalOpenings() + " < 6");
        }
        if (f.contains("window_rhythm") || f.contains("repetition")) {
            if (facade.openingDistinctYs() < 3) {
                issues.add("openingDistinctYs=" + facade.openingDistinctYs() + " < 3 (weak floor rhythm)");
            }
            if (facade.southRhythmScore() < 0.35 && facade.totalOpenings() >= 2) {
                issues.add(String.format(Locale.ROOT,
                        "southRhythmScore=%.2f < 0.35", facade.southRhythmScore()));
            }
            // Curtain-wall sheets fail this; bay rhythm needs solid mullions/piers between openings.
            if (facade.southOpenings() >= 4 && facade.southPierGapRatio() < 0.12) {
                issues.add(String.format(Locale.ROOT,
                        "southPierGapRatio=%.2f < 0.12 (missing window piers)",
                        facade.southPierGapRatio()));
            }
        }
        return new Report(roof, facade, List.copyOf(issues));
    }

    static RoofReport analyzeRoof(Map<BlockPos, String> cells) {
        return analyzeRoof(cells, null);
    }

    static RoofReport analyzeRoof(Map<BlockPos, String> cells, List<BlockPatch> patches) {
        if (cells.isEmpty()) {
            return new RoofReport(0, 0, 0, 0, 0, false, 0);
        }
        Map<Long, Integer> maxYByColumn = new TreeMap<>();
        int globalMinY = Integer.MAX_VALUE;
        int globalMaxY = Integer.MIN_VALUE;
        int shaped = 0;
        for (var e : cells.entrySet()) {
            BlockPos p = e.getKey();
            long key = pack(p.getX(), p.getZ());
            maxYByColumn.merge(key, p.getY(), Math::max);
            globalMinY = Math.min(globalMinY, p.getY());
            globalMaxY = Math.max(globalMaxY, p.getY());
            String b = e.getValue() == null ? "" : e.getValue().toLowerCase(Locale.ROOT);
            if (b.contains("stairs") || b.contains("slab")) shaped++;
        }
        if (patches != null) {
            // Prefer counting from final cells; patches already collapsed above.
        }

        // Pitched / multi-eave roofs have eaves far below the ridge. Measure coverage
        // against the upper height band, not only globalMaxY-1.
        int heightSpan = Math.max(1, globalMaxY - globalMinY + 1);
        int roofBandMin = globalMinY + (int) Math.floor(heightSpan * 0.50);

        int footprint = maxYByColumn.size();
        int covered = 0;
        int peak = 0;
        int minTop = Integer.MAX_VALUE;
        int maxTop = Integer.MIN_VALUE;
        Set<Integer> topYs = new LinkedHashSet<>();
        for (Integer y : maxYByColumn.values()) {
            minTop = Math.min(minTop, y);
            maxTop = Math.max(maxTop, y);
            if (y >= roofBandMin) {
                covered++;
                topYs.add(y);
            }
            if (y == globalMaxY) peak++;
        }
        int rise = (minTop == Integer.MAX_VALUE) ? 0 : (maxTop - minTop + 1);
        boolean pitched = topYs.size() >= 2 && rise >= 2;
        double ratio = footprint == 0 ? 0 : (double) covered / (double) footprint;
        return new RoofReport(footprint, covered, ratio, rise, peak, pitched, shaped);
    }

    static FacadeReport analyzeFacade(Map<BlockPos, String> cells) {
        if (cells.isEmpty()) {
            return new FacadeReport(0, 0, 0, 0, 0, 0, 0, 0);
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : cells.keySet()) {
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
        }
        // Face axes match MassMainGenerator / FacadeWindowsGenerator / SizedFacadeWindows:
        // SOUTH=-Z(minZ), NORTH=+Z(maxZ), EAST=-X(minX), WEST=+X(maxX).
        int south = 0, north = 0, east = 0, west = 0;
        Set<Integer> openingYs = new LinkedHashSet<>();
        Map<Integer, Integer> southOpeningsByX = new LinkedHashMap<>();
        Set<Integer> southOpeningXs = new LinkedHashSet<>();
        for (var e : cells.entrySet()) {
            BlockPos p = e.getKey();
            String b = e.getValue() == null ? "" : e.getValue().toLowerCase(Locale.ROOT);
            if (!isOpening(b)) continue;
            openingYs.add(p.getY());
            if (p.getZ() == minZ) {
                south++;
                southOpeningsByX.merge(p.getX(), 1, Integer::sum);
                southOpeningXs.add(p.getX());
            } else if (p.getZ() == maxZ) {
                north++;
            } else if (p.getX() == minX) {
                east++;
            } else if (p.getX() == maxX) {
                west++;
            } else {
                // Interior-ish openings still count toward total via face-nearest
                int dxMin = Math.min(p.getX() - minX, maxX - p.getX());
                int dzMin = Math.min(p.getZ() - minZ, maxZ - p.getZ());
                if (dzMin <= dxMin) {
                    if (p.getZ() - minZ <= maxZ - p.getZ()) {
                        south++;
                        southOpeningsByX.merge(p.getX(), 1, Integer::sum);
                        southOpeningXs.add(p.getX());
                    } else {
                        north++;
                    }
                } else {
                    if (p.getX() - minX <= maxX - p.getX()) east++;
                    else west++;
                }
            }
        }
        int total = south + north + east + west;
        double rhythm = rhythmScore(southOpeningsByX, minX, maxX);
        double pierGap = pierGapRatio(southOpeningXs);
        return new FacadeReport(south, north, east, west, total, openingYs.size(), rhythm, pierGap);
    }

    /**
     * Fraction of the span between the first and last SOUTH opening columns that is not itself
     * an opening — i.e. mullion / pier cells. Continuous glass sheets score near 0.
     */
    private static double pierGapRatio(Set<Integer> openingXs) {
        if (openingXs == null || openingXs.size() < 2) return openingXs == null || openingXs.isEmpty() ? 0 : 1.0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int x : openingXs) {
            min = Math.min(min, x);
            max = Math.max(max, x);
        }
        int span = Math.max(1, max - min + 1);
        int pier = span - openingXs.size();
        return Math.max(0, (double) pier / (double) span);
    }

    /** 0..1 — openings spread across SOUTH face X span (not clustered in one bay). */
    private static double rhythmScore(Map<Integer, Integer> byX, int minX, int maxX) {
        int span = Math.max(1, maxX - minX + 1);
        if (byX.isEmpty()) return 0;
        int occupiedBays = 0;
        // Divide into up to 4 equal bays
        int bays = Math.min(4, span);
        int bayW = Math.max(1, (span + bays - 1) / bays);
        boolean[] hit = new boolean[bays];
        for (int x : byX.keySet()) {
            int idx = Math.min(bays - 1, Math.max(0, (x - minX) / bayW));
            hit[idx] = true;
        }
        for (boolean b : hit) if (b) occupiedBays++;
        return (double) occupiedBays / (double) bays;
    }

    private static boolean isOpening(String block) {
        return block.contains("glass") || block.contains("door") || block.contains("pane");
    }

    private static long pack(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }
}
