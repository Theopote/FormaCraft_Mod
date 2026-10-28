package com.formacraft.server.edit;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Storey bands derived from building volume + live solid density — not a fixed 4-block layer height.
 */
public final class BuildingFloorBands {
    public record FloorBand(int floorIndex, int minY, int maxYExclusive) {
        public FloorBand {
            floorIndex = Math.max(0, floorIndex);
            if (maxYExclusive < minY) maxYExclusive = minY;
        }

        public boolean containsY(int y) {
            return y >= minY && y < maxYExclusive;
        }
    }

    private BuildingFloorBands() {}

    /**
     * Infer floor plates: Y levels with enough opaque cells inside the building AABB,
     * then form bands between consecutive plates (and up to the roof/max).
     */
    public static List<FloorBand> fromLive(BuildingInstance building, Map<BlockPos, BlockState> live) {
        if (building == null) return List.of(new FloorBand(0, 0, 4));
        int minY = building.min().getY();
        int maxY = building.max().getY();
        if (live == null || live.isEmpty()) {
            return List.of(new FloorBand(0, minY, Math.max(minY + 1, maxY + 1)));
        }

        int minX = building.min().getX();
        int maxX = building.max().getX();
        int minZ = building.min().getZ();
        int maxZ = building.max().getZ();
        int footprint = Math.max(1, (maxX - minX + 1) * (maxZ - minZ + 1));
        // Wall shells alone must not count as storeys — require interior (non-perimeter) solids
        // or near-full footprint coverage typical of real floor plates.
        int interiorThreshold = Math.max(2, footprint / 20);
        int coverageThreshold = Math.max(interiorThreshold * 3, (footprint * 3) / 5);

        List<Integer> plates = new ArrayList<>();
        for (int y = minY; y <= maxY; y++) {
            int solids = 0;
            int interior = 0;
            for (var e : live.entrySet()) {
                BlockPos p = e.getKey();
                if (p == null || p.getY() != y) continue;
                if (!building.contains(p)) continue;
                BlockState s = e.getValue();
                if (s == null || !s.isOpaque()) continue;
                solids++;
                boolean perimeter = p.getX() == minX || p.getX() == maxX
                        || p.getZ() == minZ || p.getZ() == maxZ;
                if (!perimeter) interior++;
            }
            if (interior >= interiorThreshold || solids >= coverageThreshold) {
                // Foundation / buried dirt: require wall shell in the next 1–2 levels.
                if (!hasWallAbove(live, building, minX, maxX, minZ, maxZ, y)) continue;
                plates.add(y);
            }
        }
        if (plates.isEmpty()) {
            // Fallback: single band spanning the building height.
            return List.of(new FloorBand(0, minY, Math.max(minY + 1, maxY + 1)));
        }

        // Build raw bands, then merge sub-storey gaps (foundation noise, 1-block mezzanines).
        List<int[]> raw = new ArrayList<>();
        for (int i = 0; i < plates.size(); i++) {
            int plateY = plates.get(i);
            int next = (i + 1 < plates.size()) ? plates.get(i + 1) : Math.max(plateY + 1, maxY + 1);
            raw.add(new int[]{plateY, Math.max(plateY + 1, next)});
        }
        List<FloorBand> bands = new ArrayList<>();
        int cursor = 0;
        while (cursor < raw.size()) {
            int bandMin = raw.get(cursor)[0];
            int bandMax = raw.get(cursor)[1];
            cursor++;
            while (cursor < raw.size() && (bandMax - bandMin) < 3) {
                bandMax = Math.max(bandMax, raw.get(cursor)[1]);
                cursor++;
            }
            // Absorb a following thin band into this storey.
            while (cursor < raw.size() && (raw.get(cursor)[1] - raw.get(cursor)[0]) < 3) {
                bandMax = Math.max(bandMax, raw.get(cursor)[1]);
                cursor++;
            }
            bands.add(new FloorBand(bands.size(), bandMin, bandMax));
        }
        return List.copyOf(bands);
    }

    private static boolean hasWallAbove(
            Map<BlockPos, BlockState> live,
            BuildingInstance building,
            int minX, int maxX, int minZ, int maxZ,
            int plateY
    ) {
        for (int dy = 1; dy <= 2; dy++) {
            int y = plateY + dy;
            int perimeter = 0;
            for (var e : live.entrySet()) {
                BlockPos p = e.getKey();
                if (p == null || p.getY() != y || !building.contains(p)) continue;
                BlockState s = e.getValue();
                if (s == null || !s.isOpaque()) continue;
                if (p.getX() == minX || p.getX() == maxX || p.getZ() == minZ || p.getZ() == maxZ) {
                    perimeter++;
                }
            }
            if (perimeter >= 4) return true;
        }
        return false;
    }

    public static int floorIndexOf(List<FloorBand> bands, int y) {
        if (bands == null || bands.isEmpty()) return 0;
        for (FloorBand band : bands) {
            if (band.containsY(y)) return band.floorIndex();
        }
        // Below first plate → floor 0; above last → last floor.
        if (y < bands.getFirst().minY()) return 0;
        return bands.getLast().floorIndex();
    }

    public static FloorBand bandOf(List<FloorBand> bands, int floorIndex) {
        if (bands == null || bands.isEmpty()) return null;
        int idx = Math.max(0, floorIndex);
        for (FloorBand band : bands) {
            if (band.floorIndex() == idx) return band;
        }
        return null;
    }
}
