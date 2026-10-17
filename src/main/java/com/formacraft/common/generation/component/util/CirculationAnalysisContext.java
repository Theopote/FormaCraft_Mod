package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;

import java.util.HashMap;
import java.util.Map;

/**
 * Compile-scoped circulation analysis: one {@link PlannedOccupancy} plus cached
 * {@link StoreyWalkRegions} scans for the bridge gate and building-circulation validator.
 * Cleared in the same begin/end window — not a cross-compile global cache.
 */
public final class CirculationAnalysisContext {
    private CirculationAnalysisContext() {}

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<PlannedOccupancy> OCCUPANCY = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, StoreyWalkRegions.ScanResult>> SCANS =
            ThreadLocal.withInitial(HashMap::new);

    public static void begin(Iterable<BlockPatch> patches) {
        end();
        ACTIVE.set(true);
        OCCUPANCY.set(BridgeWalkability.occupancyFromPatches(patches));
        SCANS.get().clear();
    }

    public static void end() {
        ACTIVE.set(false);
        OCCUPANCY.remove();
        SCANS.get().clear();
        SCANS.remove();
        ACTIVE.remove();
    }

    public static boolean isActive() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    /** Shared occupancy when active; otherwise null (callers build their own). */
    public static PlannedOccupancy occupancy() {
        return isActive() ? OCCUPANCY.get() : null;
    }

    /**
     * Scan (or return cached) storey regions. When inactive, delegates to
     * {@link StoreyWalkRegions#scan} with no caching.
     */
    public static StoreyWalkRegions.ScanResult scanStorey(
            PlannedOccupancy occ,
            Component mass,
            int storeyY,
            Vec3i massOffset
    ) {
        if (!isActive()) {
            return StoreyWalkRegions.scan(occ, mass, storeyY, massOffset);
        }
        PlannedOccupancy use = occ != null ? occ : OCCUPANCY.get();
        String key = scanKey(mass, storeyY, massOffset);
        Map<String, StoreyWalkRegions.ScanResult> cache = SCANS.get();
        StoreyWalkRegions.ScanResult cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        StoreyWalkRegions.ScanResult scan = StoreyWalkRegions.scan(use, mass, storeyY, massOffset);
        cache.put(key, scan);
        return scan;
    }

    /** Visible for tests: number of cached storey scans in the active context. */
    public static int cachedScanCount() {
        return isActive() ? SCANS.get().size() : 0;
    }

    private static String scanKey(Component mass, int storeyY, Vec3i massOffset) {
        String id = "unknown";
        if (mass != null && mass.params() != null && mass.params().get("component_id") != null) {
            id = String.valueOf(mass.params().get("component_id")).trim();
        }
        int ox = massOffset == null ? 0 : massOffset.x();
        int oy = massOffset == null ? 0 : massOffset.y();
        int oz = massOffset == null ? 0 : massOffset.z();
        return id + "|" + storeyY + "|" + ox + "," + oy + "," + oz;
    }
}
