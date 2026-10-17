package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Compile-scoped circulation analysis: one {@link PlannedOccupancy} plus cached
 * {@link StoreyWalkRegions} scans for the bridge gate and building-circulation validator.
 * Scan keys use {@link Component} <em>identity</em> (not {@code component_id} strings).
 * Cleared in the same begin/end window — not a cross-compile global cache.
 */
public final class CirculationAnalysisContext {
    private CirculationAnalysisContext() {}

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<PlannedOccupancy> OCCUPANCY = new ThreadLocal<>();
    private static final ThreadLocal<Map<ScanKey, StoreyWalkRegions.ScanResult>> SCANS =
            ThreadLocal.withInitial(HashMap::new);

    /**
     * Cache key: same Component instance + storeyY + slot offset.
     * Different Component objects never collide, even with missing or equal component_id.
     */
    private static final class ScanKey {
        private final Component component;
        private final int storeyY;
        private final int ox;
        private final int oy;
        private final int oz;

        ScanKey(Component component, int storeyY, Vec3i massOffset) {
            this.component = component;
            this.storeyY = storeyY;
            this.ox = massOffset == null ? 0 : massOffset.x();
            this.oy = massOffset == null ? 0 : massOffset.y();
            this.oz = massOffset == null ? 0 : massOffset.z();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ScanKey other)) {
                return false;
            }
            return component == other.component
                    && storeyY == other.storeyY
                    && ox == other.ox
                    && oy == other.oy
                    && oz == other.oz;
        }

        @Override
        public int hashCode() {
            return Objects.hash(System.identityHashCode(component), storeyY, ox, oy, oz);
        }
    }

    public static void begin(Iterable<BlockPatch> patches) {
        end();
        try {
            PlannedOccupancy occ = BridgeWalkability.occupancyFromPatches(patches);
            OCCUPANCY.set(occ);
            SCANS.get().clear();
            ACTIVE.set(true);
        } catch (RuntimeException e) {
            end();
            throw e;
        }
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
     * When active, always uses the context-held occupancy (caller {@code occ} is ignored).
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
        PlannedOccupancy use = OCCUPANCY.get();
        ScanKey key = new ScanKey(mass, storeyY, massOffset);
        Map<ScanKey, StoreyWalkRegions.ScanResult> cache = SCANS.get();
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
}
