package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.HashMap;
import java.util.Map;

/**
 * Compile-scoped cache of {@link ResolvedBuildingGeometry} keyed by mass {@code component_id}.
 * Populated only inside a scope; closing restores the enclosing cache or releases thread state.
 * Generators must prefer this over re-resolving host topology.
 */
public final class BuildingGeometryContext {
    private BuildingGeometryContext() {}

    public record Entry(ResolvedBuildingGeometry geometry, Vec3i originMinCorner) {}

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
        if (componentId == null || componentId.isBlank() || geometry == null || originMinCorner == null) {
            return;
        }
        var cache = CACHE.get();
        if (cache != null) cache.put(componentId, new Entry(geometry, originMinCorner));
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
        ResolvedBuildingGeometry geom = ResolvedBuildingGeometry.resolve(mass);
        if (geom == null) {
            return;
        }
        Vec3i origin = ComponentFootprintUtil.resolveMinCornerOrigin(mass);
        put(componentId, geom, origin);
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
            ResolvedBuildingGeometry geom = ResolvedBuildingGeometry.resolve(mass);
            if (geom == null || mass.relativePosition() == null) {
                return null;
            }
            return new Entry(geom, ComponentFootprintUtil.resolveMinCornerOrigin(mass));
        }
        putMass(mass);
        return get(id);
    }
}
