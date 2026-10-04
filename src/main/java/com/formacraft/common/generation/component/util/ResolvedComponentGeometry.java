package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import java.util.ArrayList;
import java.util.List;

/** Shared geometry for the legacy components route. Bounds are half-open; Y is always bottom Y. */
public record ResolvedComponentGeometry(
        Component source, Vec3i origin, Dimensions dimensions, int floorHeight,
        List<Integer> floorYs, boolean floorLayoutFits) {

    public static Component normalizeBody(Component component) {
        if (component == null || component.dimensions() == null) return component;
        String type = component.componentType();
        if (!("MASS_MAIN".equalsIgnoreCase(type) || "MAIN_MASS".equalsIgnoreCase(type))) return component;
        if (component.params() != null && "plate".equals(component.params().get("extrude_mode"))) return component;
        Dimensions d = component.dimensions();
        int floorHeight = explicitFloorHeight(component);
        Dimensions normalized = new Dimensions(Math.max(3, d.width()), Math.max(3, d.depth()),
                Math.max(Math.max(3, d.height()), floorHeight));
        if (normalized.equals(d)) return component;
        return new Component(type, component.slotId(), component.relativePosition(), normalized,
                component.features(), component.params());
    }

    public static int explicitFloorHeight(Component component) {
        if (component == null) return 0;
        int height = ComponentParamParsers.intParam(component.params(), 0, "floor_height", "floorHeight");
        return height > 0 ? height : Math.max(0,
                ProportionalFacadeCalculator.extractFloorHeightFromFeatures(component.features()));
    }

    public static ResolvedComponentGeometry resolve(Component component) {
        if (component == null || component.relativePosition() == null || component.dimensions() == null) return null;
        Component body = normalizeBody(component);
        Dimensions d = body.dimensions();
        Vec3i p = body.relativePosition();
        Vec3i origin = ComponentFootprintUtil.isCornerAnchor(body.params()) ? p
                : new Vec3i(p.x() - Math.max(1, d.width()) / 2, p.y(), p.z() - Math.max(1, d.depth()) / 2);
        int height = explicitFloorHeight(body);
        int count = ComponentParamParsers.intParam(body.params(), 0, "floor_count", "floorCount");
        List<Integer> levels = new ArrayList<>();
        if (height > 0 && count > 0) {
            // Only materialize levels inside the actual envelope, with bounded work for invalid counts.
            int fitting = Math.min(count, (Math.max(1, d.height()) - 1) / height + 1);
            for (int floor = 0; floor < fitting; floor++) levels.add(origin.y() + floor * height);
        }
        boolean fits = height <= 0 || count <= 0 || (long) height * count <= d.height();
        return new ResolvedComponentGeometry(body, origin, d, height, List.copyOf(levels), fits);
    }

    public int roofY() { return origin.y() + Math.max(1, dimensions.height()) - 1; }

    public ComponentFootprintUtil.Bounds bounds() {
        return new ComponentFootprintUtil.Bounds(origin.x(), origin.y(), origin.z(),
                origin.x() + Math.max(1, dimensions.width()), origin.y() + Math.max(1, dimensions.height()),
                origin.z() + Math.max(1, dimensions.depth()));
    }

    /** Slot translation enters plan space; the world anchor is applied only by placement. */
    public Vec3i originInPlan(Vec3i slotAnchor) {
        return translate(origin, slotAnchor);
    }

    public Vec3i originInWorld(Vec3i slotAnchor, Vec3i planAnchor) {
        return translate(originInPlan(slotAnchor), planAnchor);
    }

    private static Vec3i translate(Vec3i origin, Vec3i offset) {
        return offset == null ? origin : new Vec3i(origin.x() + offset.x(), origin.y() + offset.y(), origin.z() + offset.z());
    }
}
