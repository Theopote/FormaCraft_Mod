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
 * LLM castles often lift TOWER bases to mid-keep Y without a supporting plate, leaving
 * curtain towers floating. Snap each tower down onto the highest overlapping WALL/MASS top
 * (or y=0 when nothing supports it) and shift same-slot satellites with it.
 */
final class FloatingTowerSnapRepair {
    private FloatingTowerSnapRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        List<Component> components = new ArrayList<>(plan.components());
        boolean changed = false;
        for (int i = 0; i < components.size(); i++) {
            Component tower = components.get(i);
            if (tower == null || tower.relativePosition() == null || tower.dimensions() == null) continue;
            if (!isTower(tower)) continue;
            int y = tower.relativePosition().y();
            if (y <= 0) continue;
            int supportTop = supportTopY(tower, components);
            if (y <= supportTop) continue;
            int dy = supportTop - y;
            FormacraftMod.LOGGER.info(
                    "FloatingTowerSnapRepair: {} y={} → {} (supportTop={})",
                    componentId(tower), y, supportTop, supportTop);
            components.set(i, shift(tower, dy));
            String towerId = componentId(tower);
            String slot = tower.slotId();
            for (int j = 0; j < components.size(); j++) {
                if (j == i) continue;
                Component c = components.get(j);
                if (c == null || c.relativePosition() == null) continue;
                if (hostedBy(c, towerId) || sameSlotSatellite(c, slot, tower)) {
                    components.set(j, shift(c, dy));
                }
            }
            changed = true;
        }
        if (!changed) return plan;
        return new LlmPlan(
                plan.mode(), plan.styleProfile(), plan.anchor(), plan.globalConstraints(), plan.layout(),
                components, plan.genome(), plan.styleAttributes(), plan.proportionHints(),
                plan.alignmentAndSymmetry(), plan.targetSlotId(), plan.allowedArea(), plan.patch(),
                plan.planProgram(), plan.planSkeleton(), plan.planStatus(), plan.error(),
                plan.capabilityGap(), plan.playerFidelityNoticeZh(), plan.distinguishingFeatures(),
                plan.enrichmentGuard(), plan.planMeta(), plan.designSources());
    }

    private static boolean isTower(Component c) {
        String t = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
        return "TOWER".equals(t) || t.startsWith("TOWER_");
    }

    private static int supportTopY(Component tower, List<Component> components) {
        var towerBounds = ComponentFootprintUtil.bounds(tower);
        if (towerBounds == null) return 0;
        int best = 0;
        for (Component c : components) {
            if (c == null || c == tower || c.relativePosition() == null || c.dimensions() == null) continue;
            if (!isSupport(c)) continue;
            var b = ComponentFootprintUtil.bounds(c);
            if (b == null || !xzOverlaps(towerBounds, b)) continue;
            int top = c.relativePosition().y() + Math.max(1, c.dimensions().height());
            best = Math.max(best, top);
        }
        return best;
    }

    private static boolean isSupport(Component c) {
        String t = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
        return "WALL".equals(t) || "MASS_MAIN".equals(t) || "MAIN_MASS".equals(t)
                || "KEEP".equals(t) || "FOUNDATION".equals(t) || "GATE".equals(t)
                || "MASS_SECONDARY".equals(t);
    }

    private static boolean xzOverlaps(ComponentFootprintUtil.Bounds a, ComponentFootprintUtil.Bounds b) {
        // Bounds max is exclusive; expand by 1 so flush face-adjacent curtain walls count.
        return a.minX() < b.maxX() + 1 && b.minX() < a.maxX() + 1
                && a.minZ() < b.maxZ() + 1 && b.minZ() < a.maxZ() + 1;
    }

    private static boolean hostedBy(Component c, String towerId) {
        if (towerId == null || c.params() == null) return false;
        Object host = c.params().get("host_id");
        if (host == null) host = c.params().get("hostId");
        return towerId.equals(String.valueOf(host));
    }

    private static boolean sameSlotSatellite(Component c, String slot, Component tower) {
        if (slot == null || !Objects.equals(slot, c.slotId())) return false;
        if (c.relativePosition() == null || tower.relativePosition() == null) return false;
        // Roofs / battlements authored above the tower base travel with the snap.
        return c.relativePosition().y() >= tower.relativePosition().y()
                && !"TOWER".equalsIgnoreCase(c.componentType());
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
