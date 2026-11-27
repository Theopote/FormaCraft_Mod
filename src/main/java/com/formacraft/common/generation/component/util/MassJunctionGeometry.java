package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared-face / junction cell extraction (Phase 02.3.3 Tasks D–E).
 * Classification: GEOMETRY — operates only on authorized contact cells.
 */
public final class MassJunctionGeometry {
    private MassJunctionGeometry() {}

    /**
     * Plan-space cells of A's shell that sit on the shared face with B (face-adjacent to B domain).
     * Used to collapse duplicate wall thickness on the later writer without stripping other walls.
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
            // Cell on A that is face-adjacent toward B
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
        // Omit secondary shell cells that overlap or sit in the primary's wall thickness band.
        Set<Long> shared = sharedFaceCellsA(
                domainSecondary, originSecondary, domainPrimary, originPrimary,
                faceOfSecondaryTowardPrimary, bandMinY, bandMaxY, secondaryThickness);
        // Also omit secondary cells that volumetrically coincide with primary solids on the seam.
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
     * Opening cells for open_connection / passage: a doorway band on the shared face.
     * Returns plan-space packed cells (wall openings start above floor slab).
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

    private static boolean domainContains(
            MassBooleanComposition.CsgResult domain,
            Vec3i origin,
            int x, int y, int z
    ) {
        return domain.contains(x - origin.x(), y - origin.y(), z - origin.z());
    }
}
