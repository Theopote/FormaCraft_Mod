package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.HashMap;
import java.util.Map;

/**
 * Compile-scoped cache of {@link ResolvedBuildingGeometry} keyed by mass {@code component_id}.
 * Populated only inside a scope; closing restores the enclosing cache or releases thread state.
 * Generators must prefer this over re-resolving host topology.
 * <p>
 * Phase 02.3.1: optional {@link MassBooleanComposition.CsgResult} domain is parsed once in
 * {@link #putMass} and reused by emit / BuildingVolume (no second compose).
 */
public final class BuildingGeometryContext {
    private BuildingGeometryContext() {}

    /**
     * @param domain post-boolean occupancy when {@link MassBooleanComposition#needsCsg}; else null.
     *               BuildingVolume AABB is coarse only — real occupancy is {@code domain}.
     */
    public record Entry(
            ResolvedBuildingGeometry geometry,
            Vec3i originMinCorner,
            MassBooleanComposition.CsgResult domain
    ) {
        /** Compat: non-CSG entry. */
        public Entry(ResolvedBuildingGeometry geometry, Vec3i originMinCorner) {
            this(geometry, originMinCorner, null);
        }
    }

    private static final ThreadLocal<Map<String, Entry>> CACHE = new ThreadLocal<>();

    /** Install an isolated cache and restore the enclosing compilation on close. */
    public static Scope open() {
        var scope = new Scope(CACHE.get());
        CACHE.set(new HashMap<>());
        return scope;
    }

    public static final class Scope implements AutoCloseable {
        private final Map<String, Entry> previous;
        private boolean closed;

        private Scope(Map<String, Entry> previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            if (previous == null) CACHE.remove();
            else CACHE.set(previous);
            closed = true;
        }
    }

    public static boolean isActive() {
        return CACHE.get() != null;
    }

    public static void put(String componentId, ResolvedBuildingGeometry geometry, Vec3i originMinCorner) {
        put(componentId, geometry, originMinCorner, null);
    }

    public static void put(
            String componentId,
            ResolvedBuildingGeometry geometry,
            Vec3i originMinCorner,
            MassBooleanComposition.CsgResult domain
    ) {
        if (componentId == null || componentId.isBlank() || geometry == null || originMinCorner == null) {
            return;
        }
        var cache = CACHE.get();
        if (cache != null) cache.put(componentId, new Entry(geometry, originMinCorner, domain));
    }

    public static void putMass(Component mass) {
        if (!isActive() || mass == null || mass.params() == null) {
            return;
        }
        Object id = mass.params().get("component_id");
        if (id == null) {
            return;
        }
        String componentId = String.valueOf(id).trim();
        if (componentId.isEmpty()) {
            return;
        }
        if (mass.params() != null && "plate".equalsIgnoreCase(String.valueOf(mass.params().get("extrude_mode")))) {
            return;
        }
        ResolvedBuildingGeometry geom;
        try {
            geom = ResolvedBuildingGeometry.resolve(mass);
        } catch (IllegalArgumentException ignored) {
            // E_PLAN_PATTERN_UNSUPPORTED / E_FOOTPRINT_SHAPE_UNSUPPORTED etc. — leave for
            // MassMainGenerator → ComponentEmitPass.geometryConstraintCode (do not abort putMass).
            return;
        }
        if (geom == null) {
            return;
        }
        Vec3i origin = ComponentFootprintUtil.resolveMinCornerOrigin(mass);
        MassBooleanComposition.CsgResult domain = null;
        if (MassBooleanComposition.needsCsg(mass)) {
            try {
                domain = MassBooleanComposition.composeFromComponent(mass);
            } catch (IllegalArgumentException ignored) {
                // Gap ownership stays with generate/fold callers; cache footprint-only.
            }
        }
        put(componentId, geom, origin, domain);
    }

    public static Entry get(String componentId) {
        if (componentId == null || componentId.isBlank()) {
            return null;
        }
        var cache = CACHE.get();
        return cache == null ? null : cache.get(componentId.trim());
    }

    /** Return cached geometry for this mass id, or resolve+cache when compile context is active. */
    public static Entry getOrResolve(Component mass) {
        if (mass == null) {
            return null;
        }
        String id = mass.params() == null || mass.params().get("component_id") == null
                ? null
                : String.valueOf(mass.params().get("component_id")).trim();
        Entry existing = get(id);
        if (existing != null) {
            return existing;
        }
        if (!isActive() || id == null || id.isEmpty()) {
            ResolvedBuildingGeometry geom;
            try {
                geom = ResolvedBuildingGeometry.resolve(mass);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
            if (geom == null || mass.relativePosition() == null) {
                return null;
            }
            MassBooleanComposition.CsgResult domain = null;
            if (MassBooleanComposition.needsCsg(mass)) {
                try {
                    domain = MassBooleanComposition.composeFromComponent(mass);
                } catch (IllegalArgumentException ignored) {
                    // leave null
                }
            }
            return new Entry(geom, ComponentFootprintUtil.resolveMinCornerOrigin(mass), domain);
        }
        putMass(mass);
        return get(id);
    }

    /**
     * Resolve CSG domain once: prefer compile cache, else compose.
     *
     * @throws IllegalArgumentException from compose when invalid
     */
    public static MassBooleanComposition.CsgResult domainOrCompose(Component mass) {
        if (mass == null) {
            throw new IllegalArgumentException("E_MASS_CSG_EMPTY: no mass");
        }
        Entry entry = getOrResolve(mass);
        if (entry != null && entry.domain() != null) {
            return entry.domain();
        }
        return MassBooleanComposition.composeFromComponent(mass);
    }
}
