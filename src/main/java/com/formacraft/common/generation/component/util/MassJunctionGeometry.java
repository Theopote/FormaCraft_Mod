package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Shared-face / junction cell extraction (Phase 02.3.3 / 02.3.3.1 Tasks D–E).
 * Classification: GEOMETRY — operates only on authorized contact cells in <strong>plan-space</strong>.
 */
public final class MassJunctionGeometry {
    private MassJunctionGeometry() {}

    /**
     * Through-thickness opening result (plan-space cells).
     * {@code insufficientContact=true} → caller emits capability_gap (no silent shrink).
     */
    public record OpeningResult(
            List<Vec3i> omitPlanA,
            List<Vec3i> omitPlanB,
            List<Vec3i> carvePlan,
            List<Vec3i> floorPlacePlan,
            Vec3i portalPlanAnchor,
            String faceATowardB,
            int doorWidth,
            int doorHeight,
            boolean insufficientContact
    ) {
        public static OpeningResult insufficient(String face) {
            return new OpeningResult(List.of(), List.of(), List.of(), List.of(),
                    null, face, 0, 0, true);
        }
    }

    /**
     * Plan-space cells of A's shell that sit on the shared face with B (face-adjacent to B domain).
     */
    public static Set<Long> sharedFaceCellsA(
            MassBooleanComposition.CsgResult domainA,
            Vec3i originA,
            MassBooleanComposition.CsgResult domainB,
            Vec3i originB,
            String faceTowardB,
            int bandMinY,
            int bandMaxY,
            int wallThicknessA
    ) {
        Set<Long> out = new LinkedHashSet<>();
        if (domainA == null || domainA.isEmpty() || domainB == null || domainB.isEmpty()) {
            return out;
        }
        Vec3i oa = originA == null ? new Vec3i(0, 0, 0) : originA;
        Vec3i ob = originB == null ? new Vec3i(0, 0, 0) : originB;
        int t = Math.max(1, wallThicknessA);
        int y0 = bandMinY;
        int y1 = Math.max(bandMinY + 1, bandMaxY);
        int dx = 0, dz = 0;
        if ("EAST".equalsIgnoreCase(faceTowardB)) dx = 1;
        else if ("WEST".equalsIgnoreCase(faceTowardB)) dx = -1;
        else if ("SOUTH".equalsIgnoreCase(faceTowardB)) dz = 1;
        else if ("NORTH".equalsIgnoreCase(faceTowardB)) dz = -1;
        else return out;

        for (var v : domainA.voxels()) {
            int x = v.x() + oa.x();
            int y = v.y() + oa.y();
            int z = v.z() + oa.z();
            if (y < y0 || y >= y1) continue;
            if (!domainA.isSolidSurface(v.x(), v.y(), v.z())) continue;
            boolean towardB = false;
            for (int step = 1; step <= t + 1; step++) {
                int nx = x + dx * step;
                int nz = z + dz * step;
                if (domainContains(domainB, ob, nx, y, nz)) {
                    towardB = true;
                    break;
                }
            }
            if (!towardB) continue;
            if (MassBooleanComposition.inPackRange3(x, y, z)) {
                out.add(MassBooleanComposition.pack3(x, y, z));
            }
        }
        return out;
    }

    /**
     * Cells to omit on the secondary mass so shared-wall thickness collapses to the primary's wall
     * without deleting walls outside the shared face band.
     */
    public static Set<Long> thicknessCollapseOmitCells(
            MassBooleanComposition.CsgResult domainSecondary,
            Vec3i originSecondary,
            MassBooleanComposition.CsgResult domainPrimary,
            Vec3i originPrimary,
            String faceOfSecondaryTowardPrimary,
            int bandMinY,
            int bandMaxY,
            int secondaryThickness
    ) {
        Set<Long> shared = sharedFaceCellsA(
                domainSecondary, originSecondary, domainPrimary, originPrimary,
                faceOfSecondaryTowardPrimary, bandMinY, bandMaxY, secondaryThickness);
        Vec3i os = originSecondary == null ? new Vec3i(0, 0, 0) : originSecondary;
        Vec3i op = originPrimary == null ? new Vec3i(0, 0, 0) : originPrimary;
        for (var v : domainSecondary.voxels()) {
            int x = v.x() + os.x();
            int y = v.y() + os.y();
            int z = v.z() + os.z();
            if (y < bandMinY || y >= bandMaxY) continue;
            if (domainContains(domainPrimary, op, x, y, z)
                    && MassBooleanComposition.inPackRange3(x, y, z)) {
                shared.add(MassBooleanComposition.pack3(x, y, z));
            }
        }
        return shared;
    }

    /**
     * Expand contact-face cells through both wall thicknesses into a continuous opening corridor.
     * Contact cells must be plan-space face-adjacent cells on A toward B.
     */
    public static OpeningResult throughThicknessOpening(
            List<Vec3i> contactCellsA,
            String faceATowardB,
            int thicknessA,
            int thicknessB,
            int doorWidth,
            int doorHeight,
            int floorSlabBottomY,
            int slabThickness
    ) {
        if (contactCellsA == null || contactCellsA.isEmpty() || faceATowardB == null) {
            return OpeningResult.insufficient(faceATowardB);
        }
        int tA = Math.max(1, thicknessA);
        int tB = Math.max(1, thicknessB);
        int width = Math.max(1, doorWidth);
        int height = Math.max(2, doorHeight);
        int slab = Math.max(1, slabThickness);
        int y0 = floorSlabBottomY + slab; // openings start above floor slab
        int y1 = y0 + height;

        int[] normal = normalOf(faceATowardB);
        if (normal == null) return OpeningResult.insufficient(faceATowardB);
        int ndx = normal[0], ndz = normal[1];

        // Continuous contact regions in the tangential plane at opening Y band.
        List<List<Vec3i>> regions = contactRegions(contactCellsA, faceATowardB, y0, y1);
        List<Vec3i> chosen = pickRegionFitting(regions, faceATowardB, width, height, y0, y1);
        if (chosen == null || chosen.isEmpty()) {
            return OpeningResult.insufficient(faceATowardB);
        }
        // Authored size must fit the contact region — never silent-shrink (02.3.4 A1).
        if (spanAlongTangent(chosen, faceATowardB) < width || ySpan(chosen) < height) {
            return OpeningResult.insufficient(faceATowardB);
        }

        List<Vec3i> aperture = clipToDoorSize(chosen, faceATowardB, width, height, y0, y1);
        // Full width×height rectangle must lie on real contact cells — no synthesize.
        if (aperture.size() < width * height) {
            return OpeningResult.insufficient(faceATowardB);
        }

        Set<Long> omitA = new LinkedHashSet<>();
        Set<Long> omitB = new LinkedHashSet<>();
        Set<Long> carve = new LinkedHashSet<>();
        Set<Long> floorPlace = new LinkedHashSet<>();
        Vec3i portalAnchor = null;

        for (Vec3i cell : aperture) {
            // A thickness inward (against normal from contact toward B = step 0..tA-1 back into A)
            for (int s = 0; s < tA; s++) {
                int x = cell.x() - ndx * s;
                int y = cell.y();
                int z = cell.z() - ndz * s;
                addPacked(omitA, x, y, z);
                addPacked(carve, x, y, z);
                if (portalAnchor == null) portalAnchor = new Vec3i(x, y, z);
            }
            // B thickness along normal into B
            for (int s = 1; s <= tB; s++) {
                int x = cell.x() + ndx * s;
                int y = cell.y();
                int z = cell.z() + ndz * s;
                addPacked(omitB, x, y, z);
                addPacked(carve, x, y, z);
            }
            // Floor continuity across seam at slab Y
            for (int s = 0; s < tA; s++) {
                addPacked(floorPlace, cell.x() - ndx * s, floorSlabBottomY, cell.z() - ndz * s);
            }
            for (int s = 0; s <= tB; s++) {
                addPacked(floorPlace, cell.x() + ndx * s, floorSlabBottomY, cell.z() + ndz * s);
            }
        }

        return new OpeningResult(
                unpack(omitA), unpack(omitB), unpack(carve), unpack(floorPlace),
                portalAnchor, faceATowardB, width, height, false);
    }

    /**
     * Legacy AABB-mid opening (compat). Prefer {@link #throughThicknessOpening} with contact cells.
     */
    public static List<Vec3i> openingCells(
            ComponentFootprintUtil.Bounds aabbA,
            ComponentFootprintUtil.Bounds aabbB,
            String faceATowardB,
            int bandMinY,
            int bandMaxY,
            int doorWidth,
            int doorHeight,
            int floorSlab
    ) {
        List<Vec3i> out = new ArrayList<>();
        if (aabbA == null || aabbB == null || faceATowardB == null) return out;
        int y0 = Math.max(bandMinY + Math.max(1, floorSlab), bandMinY + 1);
        int y1 = Math.min(bandMaxY, y0 + Math.max(2, doorHeight));
        int width = Math.max(1, doorWidth);

        if ("EAST".equalsIgnoreCase(faceATowardB) || "WEST".equalsIgnoreCase(faceATowardB)) {
            int x = "EAST".equalsIgnoreCase(faceATowardB) ? aabbA.maxX() - 1 : aabbA.minX();
            int z0 = Math.max(aabbA.minZ(), aabbB.minZ());
            int z1 = Math.min(aabbA.maxZ(), aabbB.maxZ());
            int mid = (z0 + z1) / 2;
            int half = width / 2;
            for (int z = mid - half; z < mid - half + width; z++) {
                if (z < z0 || z >= z1) continue;
                for (int y = y0; y < y1; y++) {
                    out.add(new Vec3i(x, y, z));
                }
            }
        } else {
            int z = "SOUTH".equalsIgnoreCase(faceATowardB) ? aabbA.maxZ() - 1 : aabbA.minZ();
            int x0 = Math.max(aabbA.minX(), aabbB.minX());
            int x1 = Math.min(aabbA.maxX(), aabbB.maxX());
            int mid = (x0 + x1) / 2;
            int half = width / 2;
            for (int x = mid - half; x < mid - half + width; x++) {
                if (x < x0 || x >= x1) continue;
                for (int y = y0; y < y1; y++) {
                    out.add(new Vec3i(x, y, z));
                }
            }
        }
        return out;
    }

    private static List<List<Vec3i>> contactRegions(
            List<Vec3i> cells, String face, int y0, int y1
    ) {
        Set<Long> inBand = new LinkedHashSet<>();
        for (Vec3i c : cells) {
            if (c.y() >= y0 && c.y() < y1 && MassBooleanComposition.inPackRange3(c.x(), c.y(), c.z())) {
                inBand.add(MassBooleanComposition.pack3(c.x(), c.y(), c.z()));
            }
        }
        // If contact was sampled only on shell surface Y outside band, relax to any Y and filter later.
        if (inBand.isEmpty()) {
            for (Vec3i c : cells) {
                if (MassBooleanComposition.inPackRange3(c.x(), c.y(), c.z())) {
                    inBand.add(MassBooleanComposition.pack3(c.x(), c.y(), c.z()));
                }
            }
        }
        List<List<Vec3i>> regions = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        boolean xAxis = "EAST".equalsIgnoreCase(face) || "WEST".equalsIgnoreCase(face);
        for (Long key : inBand) {
            if (seen.contains(key)) continue;
            List<Vec3i> region = new ArrayList<>();
            ArrayDeque<Long> q = new ArrayDeque<>();
            q.add(key);
            seen.add(key);
            while (!q.isEmpty()) {
                long k = q.removeFirst();
                int x = MassBooleanComposition.unpack3X(k);
                int y = MassBooleanComposition.unpack3Y(k);
                int z = MassBooleanComposition.unpack3Z(k);
                region.add(new Vec3i(x, y, z));
                // 4-connect in face plane (Y + tangent)
                int[][] deltas = xAxis
                        ? new int[][]{{0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}
                        : new int[][]{{0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}};
                for (int[] d : deltas) {
                    int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                    if (!MassBooleanComposition.inPackRange3(nx, ny, nz)) continue;
                    long nk = MassBooleanComposition.pack3(nx, ny, nz);
                    if (inBand.contains(nk) && seen.add(nk)) q.add(nk);
                }
            }
            regions.add(region);
        }
        return regions;
    }

    private static List<Vec3i> pickRegionFitting(
            List<List<Vec3i>> regions, String face, int width, int height, int y0, int y1
    ) {
        List<Vec3i> best = null;
        int bestScore = -1;
        for (List<Vec3i> region : regions) {
            List<Vec3i> inY = new ArrayList<>();
            for (Vec3i c : region) {
                if (c.y() >= y0 && c.y() < y1) inY.add(c);
            }
            if (inY.isEmpty()) inY = region;
            int span = spanAlongTangent(inY, face);
            int ySpan = ySpan(inY);
            // Require full authored height — never Math.min(height, 2) soft pass (02.3.4 A1).
            if (span >= width && ySpan >= height) {
                int score = span * ySpan;
                if (score > bestScore) {
                    bestScore = score;
                    best = inY;
                }
            }
        }
        return best;
    }

    /**
     * Clip to door size using <strong>only</strong> real contact cells.
     * Missing cells → empty list (caller emits capability_gap; no synthesize).
     */
    private static List<Vec3i> clipToDoorSize(
            List<Vec3i> region, String face, int width, int height, int y0, int y1
    ) {
        if (region.isEmpty()) return List.of();
        boolean xAxis = "EAST".equalsIgnoreCase(face) || "WEST".equalsIgnoreCase(face);
        Set<Long> regionKeys = new LinkedHashSet<>();
        int tMin = Integer.MAX_VALUE, tMax = Integer.MIN_VALUE;
        int yMin = Integer.MAX_VALUE, yMax = Integer.MIN_VALUE;
        for (Vec3i c : region) {
            if (!MassBooleanComposition.inPackRange3(c.x(), c.y(), c.z())) continue;
            regionKeys.add(MassBooleanComposition.pack3(c.x(), c.y(), c.z()));
            int t = xAxis ? c.z() : c.x();
            tMin = Math.min(tMin, t);
            tMax = Math.max(tMax, t);
            yMin = Math.min(yMin, c.y());
            yMax = Math.max(yMax, c.y());
        }
        if (regionKeys.isEmpty()) return List.of();
        int mid = (tMin + tMax) / 2;
        int half = width / 2;
        int t0 = mid - half;
        int t1 = t0 + width;
        int yy0 = Math.max(y0, yMin);
        int yy1 = Math.min(y0 + height, yMax + 1);
        if (yy1 - yy0 < height) {
            return List.of(); // contact band shorter than authored door height
        }
        int faceCoord = xAxis ? region.getFirst().x() : region.getFirst().z();
        List<Vec3i> out = new ArrayList<>(width * height);
        for (int t = t0; t < t1; t++) {
            for (int y = yy0; y < yy0 + height; y++) {
                int x = xAxis ? faceCoord : t;
                int z = xAxis ? t : faceCoord;
                if (!MassBooleanComposition.inPackRange3(x, y, z)) return List.of();
                long key = MassBooleanComposition.pack3(x, y, z);
                if (!regionKeys.contains(key)) {
                    return List.of(); // hole in contact — no synthesize (02.3.4 A1 / P0-01)
                }
                out.add(new Vec3i(x, y, z));
            }
        }
        return out;
    }

    private static int spanAlongTangent(List<Vec3i> cells, String face) {
        if (cells.isEmpty()) return 0;
        boolean xAxis = "EAST".equalsIgnoreCase(face) || "WEST".equalsIgnoreCase(face);
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (Vec3i c : cells) {
            int t = xAxis ? c.z() : c.x();
            min = Math.min(min, t);
            max = Math.max(max, t);
        }
        return max - min + 1;
    }

    private static int ySpan(List<Vec3i> cells) {
        if (cells.isEmpty()) return 0;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (Vec3i c : cells) {
            min = Math.min(min, c.y());
            max = Math.max(max, c.y());
        }
        return max - min + 1;
    }

    private static int[] normalOf(String face) {
        if (face == null) return null;
        return switch (face.toUpperCase(Locale.ROOT)) {
            case "EAST" -> new int[]{1, 0};
            case "WEST" -> new int[]{-1, 0};
            case "SOUTH" -> new int[]{0, 1};
            case "NORTH" -> new int[]{0, -1};
            default -> null;
        };
    }

    private static void addPacked(Set<Long> set, int x, int y, int z) {
        if (MassBooleanComposition.inPackRange3(x, y, z)) {
            set.add(MassBooleanComposition.pack3(x, y, z));
        }
    }

    private static List<Vec3i> unpack(Set<Long> keys) {
        List<Vec3i> out = new ArrayList<>(keys.size());
        for (long k : keys) {
            out.add(new Vec3i(
                    MassBooleanComposition.unpack3X(k),
                    MassBooleanComposition.unpack3Y(k),
                    MassBooleanComposition.unpack3Z(k)));
        }
        return out;
    }

    private static boolean domainContains(
            MassBooleanComposition.CsgResult domain,
            Vec3i origin,
            int x, int y, int z
    ) {
        return domain.contains(x - origin.x(), y - origin.y(), z - origin.z());
    }
}
