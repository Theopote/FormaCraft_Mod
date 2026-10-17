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

/**
 * Standable connected components on one storey of a mass footprint (plan-geometry).
 * Large footprints fall back to a single {@link BridgeWalkability#interiorFloorCell}.
 */
public final class StoreyWalkRegions {
    static final int MAX_CELLS = 4096;

    private StoreyWalkRegions() {}

    /**
     * One representative per 4-connected standable component at {@code storeyY}
     * inside the mass footprint inset by {@code wall_thickness}. Empty if none.
     */
    public static List<Vec3i> regionRepresentatives(PlannedOccupancy occ, Component mass, int storeyY) {
        if (occ == null || mass == null) {
            return List.of();
        }
        var bounds = ComponentFootprintUtil.bounds(mass);
        if (bounds == null) {
            return List.of();
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
            return List.of();
        }
        if (area > MAX_CELLS) {
            Vec3i fallback = BridgeWalkability.interiorFloorCell(mass, storeyY);
            if (fallback != null && WalkabilityGraph.canStand(occ, fallback)) {
                return List.of(fallback);
            }
            return List.of();
        }

        List<Vec3i> standable = new ArrayList<>();
        for (int x = minX; x < maxX; x++) {
            for (int z = minZ; z < maxZ; z++) {
                if (WalkabilityGraph.canStand(occ, x, storeyY, z)) {
                    standable.add(new Vec3i(x, storeyY, z));
                }
            }
        }
        if (standable.isEmpty()) {
            return List.of();
        }

        Set<Long> remaining = new HashSet<>();
        for (Vec3i c : standable) {
            remaining.add(pack(c.x(), c.z()));
        }
        Map<Long, Vec3i> byKey = new HashMap<>();
        for (Vec3i c : standable) {
            byKey.put(pack(c.x(), c.z()), c);
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
                int cx = cell.x();
                int cz = cell.z();
                for (int[] d : dirs) {
                    long nk = pack(cx + d[0], cz + d[1]);
                    if (remaining.remove(nk)) {
                        q.add(nk);
                    }
                }
            }
            reps.add(centroidNearest(component));
        }
        return List.copyOf(reps);
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
