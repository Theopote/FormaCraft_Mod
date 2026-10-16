package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.HashMap;
import java.util.Map;

/**
 * Compile-scoped cache of {@link ResolvedBuildingGeometry} keyed by mass {@code component_id}.
 * Populated by the plan compiler; cleared in the same try/finally as other compile ThreadLocals.
 * Generators must prefer this over re-resolving host topology.
 */
public final class BuildingGeometryContext {
    private BuildingGeometryContext() {}

    public record Entry(ResolvedBuildingGeometry geometry, Vec3i originMinCorner) {}

    private static final ThreadLocal<Map<String, Entry>> CACHE = ThreadLocal.withInitial(HashMap::new);
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);

    public static void begin() {
        CACHE.get().clear();
        ACTIVE.set(true);
    }

    public static void end() {
        CACHE.get().clear();
        ACTIVE.set(false);
        CACHE.remove();
        ACTIVE.remove();
    }

    public static boolean isActive() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    public static void put(String componentId, ResolvedBuildingGeometry geometry, Vec3i originMinCorner) {
        if (componentId == null || componentId.isBlank() || geometry == null || originMinCorner == null) {
            return;
        }
        CACHE.get().put(componentId, new Entry(geometry, originMinCorner));
    }

    public static void putMass(Component mass) {
        if (mass == null || mass.params() == null) {
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
        return CACHE.get().get(componentId.trim());
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
