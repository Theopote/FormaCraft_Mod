package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Snap floating primary masses (apartments / halls with y&gt;0 and no support) down onto
 * foundations or y=0 so buildings land instead of hovering above terrain.
 */
final class FloatingMassSnapRepair {
    private FloatingMassSnapRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        List<Component> components = new ArrayList<>(plan.components());
        long primaryCount = components.stream().filter(FloatingMassSnapRepair::isPrimaryMass).count();
        // Tiered compounds (palace terraces) intentionally stack masses — do not flatten them.
        if (primaryCount != 1) return plan;
        boolean changed = false;
        for (int i = 0; i < components.size(); i++) {
            Component mass = components.get(i);
            if (mass == null || mass.relativePosition() == null || mass.dimensions() == null) continue;
            if (!isPrimaryMass(mass)) continue;
            int y = mass.relativePosition().y();
            if (y <= 0) continue;
            int supportTop = supportTopY(mass, components);
            if (y <= supportTop) continue;
            int dy = supportTop - y;
            FormacraftMod.LOGGER.info(
                    "FloatingMassSnapRepair: {} y={} → {} (supportTop={})",
                    componentId(mass), y, supportTop, supportTop);
            components.set(i, shift(mass, dy));
            String massId = componentId(mass);
            String slot = mass.slotId();
            for (int j = 0; j < components.size(); j++) {
                if (j == i) continue;
                Component c = components.get(j);
                if (c == null || c.relativePosition() == null) continue;
                if (hostedBy(c, massId) || sameSlotSatellite(c, slot, mass)) {
                    components.set(j, shift(c, dy));
                }
            }
            changed = true;
        }
        return changed ? PlanComponentRewrite.withComponents(plan, components) : plan;
    }

    private static boolean isPrimaryMass(Component c) {
        String t = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
        return "MASS_MAIN".equals(t) || "MAIN_MASS".equals(t);
    }

    private static int supportTopY(Component mass, List<Component> components) {
        var massBounds = ComponentFootprintUtil.bounds(mass);
        if (massBounds == null) return 0;
        int best = 0;
        for (Component c : components) {
            if (c == null || c == mass || c.relativePosition() == null || c.dimensions() == null) continue;
            if (!isSupport(c)) continue;
            var b = ComponentFootprintUtil.bounds(c);
            if (b == null || !xzOverlaps(massBounds, b)) continue;
            int top = c.relativePosition().y() + Math.max(1, c.dimensions().height());
            best = Math.max(best, top);
        }
        return best;
    }

    private static boolean isSupport(Component c) {
        String t = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
        return "FOUNDATION".equals(t) || "TERRACE".equals(t) || "BASE".equals(t)
                || "KEEP".equals(t) || "WALL".equals(t);
    }

    private static boolean xzOverlaps(ComponentFootprintUtil.Bounds a, ComponentFootprintUtil.Bounds b) {
        return a.minX() < b.maxX() + 1 && b.minX() < a.maxX() + 1
                && a.minZ() < b.maxZ() + 1 && b.minZ() < a.maxZ() + 1;
    }

    private static boolean hostedBy(Component c, String massId) {
        if (massId == null || c.params() == null) return false;
        Object host = c.params().get("host_id");
        if (host == null) host = c.params().get("hostId");
        return massId.equals(String.valueOf(host));
    }

    private static boolean sameSlotSatellite(Component c, String slot, Component mass) {
        if (slot == null || !Objects.equals(slot, c.slotId())) return false;
        if (c.relativePosition() == null || mass.relativePosition() == null) return false;
        if (isPrimaryMass(c)) return false;
        return c.relativePosition().y() >= mass.relativePosition().y();
    }

    private static String componentId(Component c) {
        if (c.params() == null || c.params().get("component_id") == null) return c.componentType();
        return String.valueOf(c.params().get("component_id"));
    }

    private static Component shift(Component c, int dy) {
        Vec3i p = c.relativePosition();
        return new Component(c.componentType(), c.slotId(),
                new Vec3i(p.x(), p.y() + dy, p.z()),
                c.dimensions(), c.features(), c.params());
    }
}
