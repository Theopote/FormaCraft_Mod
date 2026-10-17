package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Standable walk-regions on one storey, scanned in occupancy (compiled patch) space.
 * Apply {@code slotOffset} before querying; returned representatives are already world-frame.
 */
public final class StoreyWalkRegions {
    /** Max inset footprint cells (width×depth) scanned per storey. */
    public static final int MAX_CELLS = 4096;

    public enum Status {
        OK,
        EMPTY,
        SCAN_LIMIT
    }

    public record ScanResult(Status status, List<Vec3i> representatives, long areaCells) {
        public static ScanResult ok(List<Vec3i> reps, long area) {
            return new ScanResult(Status.OK, List.copyOf(reps), area);
        }

        public static ScanResult empty(long area) {
            return new ScanResult(Status.EMPTY, List.of(), area);
        }

        public static ScanResult limit(long area) {
            return new ScanResult(Status.SCAN_LIMIT, List.of(), area);
        }
    }

    private StoreyWalkRegions() {}

    /**
     * Scan standable cells in the mass footprint at {@code localStoreyY}, translated by
     * {@code slotOffset} into occupancy space. Representatives are occupancy coordinates.
     * Area above {@link #MAX_CELLS} returns {@link Status#SCAN_LIMIT} (incomplete — not a
     * proof that walkable regions are connected).
     */
    public static ScanResult scan(
            PlannedOccupancy occ,
            Component mass,
            int localStoreyY,
            Vec3i slotOffset
    ) {
        return scan(occ, mass, localStoreyY, slotOffset, null);
    }

    /**
     * Package-visible overload: {@code edgeOpen} replaces {@link WalkabilityGraph#canTraverse}
     * during flood when non-null (tests only).
     */
    static ScanResult scan(
            PlannedOccupancy occ,
            Component mass,
            int localStoreyY,
            Vec3i slotOffset,
            BiPredicate<Vec3i, Vec3i> edgeOpen
    ) {
        if (occ == null || mass == null) {
            return ScanResult.empty(0);
        }
        Vec3i off = slotOffset == null ? new Vec3i(0, 0, 0) : slotOffset;
        var bounds = ComponentFootprintUtil.bounds(mass);
        if (bounds == null) {
            return ScanResult.empty(0);
        }
        int thickness = Math.max(1, ComponentParamParsers.intParam(
                mass.params(), 1, "wall_thickness", "wallThickness"));
        int minX = bounds.minX() + thickness;
        int maxX = bounds.maxX() - thickness;
        int minZ = bounds.minZ() + thickness;
        int maxZ = bounds.maxZ() - thickness;
        if (minX >= maxX || minZ >= maxZ) {
            minX = bounds.minX();
            maxX = bounds.maxX();
            minZ = bounds.minZ();
            maxZ = bounds.maxZ();
        }
        long area = (long) Math.max(0, maxX - minX) * Math.max(0, maxZ - minZ);
        if (area <= 0) {
            return ScanResult.empty(0);
        }
        if (area > MAX_CELLS) {
            return ScanResult.limit(area);
        }

        int worldY = localStoreyY + off.y();
        int wMinX = minX + off.x();
        int wMaxX = maxX + off.x();
        int wMinZ = minZ + off.z();
        int wMaxZ = maxZ + off.z();

        List<Vec3i> standable = new ArrayList<>();
        for (int x = wMinX; x < wMaxX; x++) {
            for (int z = wMinZ; z < wMaxZ; z++) {
                if (WalkabilityGraph.canStand(occ, x, worldY, z)) {
                    standable.add(new Vec3i(x, worldY, z));
                }
            }
        }
        if (standable.isEmpty()) {
            return ScanResult.empty(area);
        }

        Set<Long> remaining = new HashSet<>();
        Map<Long, Vec3i> byKey = new HashMap<>();
        for (Vec3i c : standable) {
            long k = pack(c.x(), c.z());
            remaining.add(k);
            byKey.put(k, c);
        }

        List<Vec3i> reps = new ArrayList<>();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!remaining.isEmpty()) {
            long startKey = remaining.iterator().next();
            List<Vec3i> component = new ArrayList<>();
            Queue<Long> q = new ArrayDeque<>();
            q.add(startKey);
            remaining.remove(startKey);
            while (!q.isEmpty()) {
                long k = q.poll();
                Vec3i cell = byKey.get(k);
                if (cell == null) {
                    continue;
                }
                component.add(cell);
                for (int[] d : dirs) {
                    long nk = pack(cell.x() + d[0], cell.z() + d[1]);
                    if (!remaining.contains(nk)) {
                        continue;
                    }
                    Vec3i next = byKey.get(nk);
                    if (next == null) {
                        continue;
                    }
                    boolean open = edgeOpen != null
                            ? edgeOpen.test(cell, next)
                            : WalkabilityGraph.canTraverse(occ, cell, next);
                    if (!open) {
                        continue;
                    }
                    remaining.remove(nk);
                    q.add(nk);
                }
            }
            reps.add(centroidNearest(component));
        }
        return ScanResult.ok(reps, area);
    }

    private static Vec3i centroidNearest(List<Vec3i> component) {
        double sx = 0;
        double sz = 0;
        for (Vec3i c : component) {
            sx += c.x();
            sz += c.z();
        }
        double cx = sx / component.size();
        double cz = sz / component.size();
        Vec3i best = component.getFirst();
        double bestDist = Double.MAX_VALUE;
        for (Vec3i c : component) {
            double dx = c.x() - cx;
            double dz = c.z() - cz;
            double dist = dx * dx + dz * dz;
            if (dist < bestDist) {
                bestDist = dist;
                best = c;
            }
        }
        return best;
    }

    private static long pack(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }
}
