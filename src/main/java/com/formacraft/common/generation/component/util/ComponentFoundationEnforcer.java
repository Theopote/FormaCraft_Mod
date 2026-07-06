package com.formacraft.common.generation.component.util;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Expands FOUNDATION footprints so they cover associated MASS_MAIN (+ margin).
 */
public final class ComponentFoundationEnforcer {

    private static final int DEFAULT_MARGIN = 2;

    private ComponentFoundationEnforcer() {}

    public static int apply(List<Component> components) {
        return apply(components, DEFAULT_MARGIN);
    }

    public static int apply(List<Component> components, int margin) {
        if (components == null || components.isEmpty()) {
            return 0;
        }
        Map<String, ComponentFootprintUtil.Bounds> massBySlot = collectMassBounds(components);
        if (massBySlot.isEmpty()) {
            return 0;
        }
        ComponentFootprintUtil.Bounds allMassUnion = unionAll(massBySlot.values());

        int expanded = 0;
        for (int i = 0; i < components.size(); i++) {
            Component c = components.get(i);
            if (c == null) {
                continue;
            }
            String type = normalizeType(c.componentType());
            if (!isFoundationOnly(type)) {
                continue;
            }
            ComponentFootprintUtil.Bounds targetMass = resolveTargetMass(c, massBySlot, allMassUnion);
            if (targetMass == null) {
                continue;
            }
            Component updated = expandToCover(c, targetMass, margin);
            if (updated != null && updated != c) {
                components.set(i, updated);
                expanded++;
            }
        }
        if (expanded > 0) {
            FormacraftMod.LOGGER.info(
                    "ComponentFoundationEnforcer: expanded {} FOUNDATION component(s) to cover MASS footprint (margin={})",
                    expanded, margin);
        }
        return expanded;
    }

    private static Map<String, ComponentFootprintUtil.Bounds> collectMassBounds(List<Component> components) {
        Map<String, ComponentFootprintUtil.Bounds> massBySlot = new HashMap<>();
        for (Component c : components) {
            if (c == null) {
                continue;
            }
            String type = normalizeType(c.componentType());
            if (!ComponentFootprintUtil.isMassType(type)) {
                continue;
            }
            ComponentFootprintUtil.Bounds b = ComponentFootprintUtil.bounds(c);
            if (b == null) {
                continue;
            }
            String slot = ComponentFootprintUtil.slotKey(c);
            massBySlot.merge(slot, b, ComponentFootprintUtil.Bounds::union);
        }
        return massBySlot;
    }

    private static ComponentFootprintUtil.Bounds resolveTargetMass(
            Component foundation,
            Map<String, ComponentFootprintUtil.Bounds> massBySlot,
            ComponentFootprintUtil.Bounds allMassUnion
    ) {
        String slot = ComponentFootprintUtil.slotKey(foundation);
        ComponentFootprintUtil.Bounds inSlot = massBySlot.get(slot);
        if (inSlot != null) {
            return inSlot;
        }
        return allMassUnion;
    }

    private static ComponentFootprintUtil.Bounds unionAll(Iterable<ComponentFootprintUtil.Bounds> bounds) {
        ComponentFootprintUtil.Bounds union = null;
        for (ComponentFootprintUtil.Bounds b : bounds) {
            union = union == null ? b : union.union(b);
        }
        return union;
    }

    private static Component expandToCover(
            Component foundation,
            ComponentFootprintUtil.Bounds massBounds,
            int margin
    ) {
        ComponentFootprintUtil.Bounds current = ComponentFootprintUtil.bounds(foundation);
        if (current == null || massBounds == null) {
            return foundation;
        }
        ComponentFootprintUtil.Bounds required = massBounds.expandHorizontal(margin);
        if (current.contains(required)) {
            return foundation;
        }
        ComponentFootprintUtil.Bounds merged = current.union(required);

        Map<String, Object> params = new HashMap<>();
        if (foundation.params() != null) {
            params.putAll(foundation.params());
        }
        params.put("anchor_mode", "min_corner");
        params.putIfAbsent("foundation_auto_expanded", true);

        return new Component(
                foundation.componentType(),
                foundation.slotId(),
                new Vec3i(merged.minX(), current.minY(), merged.minZ()),
                new Dimensions(merged.width(), merged.depth(), current.height()),
                foundation.features() != null ? new ArrayList<>(foundation.features()) : List.of(),
                params
        );
    }

    private static boolean isFoundationOnly(String type) {
        if (type == null) {
            return false;
        }
        return type.contains("FOUNDATION") || "BASE".equals(type);
    }

    private static String normalizeType(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }
}
