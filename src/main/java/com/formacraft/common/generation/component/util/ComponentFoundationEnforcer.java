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
 * Hostless foundations expand only onto primary masses with XZ overlap —
 * never onto distant sole MASS_SECONDARY decks/bridges, and never the slot-wide union.
 */
public final class ComponentFoundationEnforcer {

    private static final int DEFAULT_MARGIN = 0;

    private ComponentFoundationEnforcer() {}

    public static int apply(List<Component> components) {
        return apply(components, DEFAULT_MARGIN);
    }

    public static int apply(List<Component> components, int margin) {
        if (components == null || components.isEmpty()) {
            return 0;
        }
        Map<String, List<Component>> massesBySlot = collectPrimaryMassesBySlot(components);

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
            // Visible plinths keep authored width/depth — expanding would rewrite AI design.
            if (ComponentFootprintUtil.isVisiblePlinth(c)) {
                continue;
            }
            ComponentFootprintUtil.Bounds targetMass = resolveTargetMass(c, components, massesBySlot);
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

    /** Hostless auto-expand candidates: MASS_MAIN only (bridges/decks are MASS_SECONDARY). */
    private static Map<String, List<Component>> collectPrimaryMassesBySlot(List<Component> components) {
        Map<String, List<Component>> massesBySlot = new HashMap<>();
        for (Component c : components) {
            if (c == null) continue;
            if (!isPrimaryMassType(c.componentType())) continue;
            if (ComponentFootprintUtil.bounds(c) == null) continue;
            massesBySlot.computeIfAbsent(ComponentFootprintUtil.slotKey(c), k -> new ArrayList<>()).add(c);
        }
        return massesBySlot;
    }

    private static boolean isPrimaryMassType(String type) {
        String normalized = normalizeType(type);
        return "MASS_MAIN".equals(normalized) || "MAIN_MASS".equals(normalized);
    }

    private static ComponentFootprintUtil.Bounds resolveTargetMass(
            Component foundation,
            List<Component> components,
            Map<String, List<Component>> massesBySlot
    ) {
        Object host = foundation.params() == null ? null : foundation.params().get("host_id");
        if (host == null && foundation.params() != null) {
            host = foundation.params().get("hostId");
        }
        if (host != null) {
            for (Component body : components) {
                if (body != null && body.params() != null
                        && host.equals(body.params().get("component_id"))) {
                    return ComponentFootprintUtil.bounds(body);
                }
            }
            return null;
        }
        List<Component> candidates = massesBySlot.getOrDefault(
                ComponentFootprintUtil.slotKey(foundation), List.of());
        var foundationBounds = ComponentFootprintUtil.bounds(foundation);
        if (foundationBounds == null) return null;
        if (candidates.size() == 1) {
            var massBounds = ComponentFootprintUtil.bounds(candidates.get(0));
            // Sole primary mass still needs XZ proximity — do not span empty ground.
            if (massBounds == null || !xzAreaOverlaps(foundationBounds, massBounds)) return null;
            return massBounds;
        }
        if (candidates.size() <= 1) {
            return null;
        }
        // Multiple primary masses: expand only onto a uniquely overlapping body.
        Component unique = null;
        for (Component mass : candidates) {
            var massBounds = ComponentFootprintUtil.bounds(mass);
            if (massBounds == null || !xzAreaOverlaps(foundationBounds, massBounds)) continue;
            if (unique != null) return null;
            unique = mass;
        }
        return unique == null ? null : ComponentFootprintUtil.bounds(unique);
    }

    private static boolean xzAreaOverlaps(ComponentFootprintUtil.Bounds a, ComponentFootprintUtil.Bounds b) {
        return a.minX() < b.maxX() && b.minX() < a.maxX()
                && a.minZ() < b.maxZ() && b.minZ() < a.maxZ();
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
