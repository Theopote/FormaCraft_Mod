package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LLM castles often lift TOWER bases to mid-keep Y without a supporting plate, leaving
 * curtain towers floating. Snap each tower down onto the highest overlapping WALL/MASS top
 * (or y=0 when nothing supports it) and shift authored host attachments with it.
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
            // Roof lanterns / oculus crowns sit on the dome apex — never snap them onto the cella top.
            if (isRoofLantern(tower)) continue;
            int y = tower.relativePosition().y();
            if (y <= 0) continue;
            // Stilts / authored floating crowns keep their elevation (design authority).
            if (preservesAuthoredElevation(tower)) continue;
            int supportTop = supportTopY(tower, components);
            if (y <= supportTop) continue;
            int dy = supportTop - y;
            FormacraftMod.LOGGER.info(
                    "FloatingTowerSnapRepair: {} y={} → {} (supportTop={})",
                    componentId(tower), y, supportTop, supportTop);
            components.set(i, shift(tower, dy));
            String towerId = componentId(tower);
            var descendants = HostAttachmentTree.descendantIndices(towerId, components);
            java.util.BitSet shifted = new java.util.BitSet();
            shifted.set(i);
            for (int j = descendants.nextSetBit(0); j >= 0; j = descendants.nextSetBit(j + 1)) {
                if (shifted.get(j)) continue;
                Component c = components.get(j);
                if (c == null || c.relativePosition() == null) continue;
                components.set(j, shift(c, dy));
                shifted.set(j);
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
        int towerY = tower.relativePosition().y();
        int best = 0;
        for (Component c : components) {
            if (c == null || c == tower || c.relativePosition() == null || c.dimensions() == null) continue;
            if (!isSupport(c)) continue;
            var b = ComponentFootprintUtil.bounds(c);
            if (b == null || !xzOverlaps(towerBounds, b)) continue;
            int top = c.relativePosition().y() + Math.max(1, c.dimensions().height());
            // Support must meet the tower from below (or flush). A roof above cannot prop it up.
            if (top > towerY) continue;
            best = Math.max(best, top);
        }
        return best;
    }

    private static boolean isSupport(Component c) {
        String t = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
        // Structural hosts only — roofs/lanterns above a tower are never load-bearing supports.
        return "WALL".equals(t) || "MASS_MAIN".equals(t) || "MAIN_MASS".equals(t)
                || "KEEP".equals(t) || "FOUNDATION".equals(t) || "GATE".equals(t)
                || "MASS_SECONDARY".equals(t) || "BASE".equals(t) || "TERRACE".equals(t);
    }

    /** Explicit stilts / floating / raised markers must not be snapped to ground. */
    private static boolean preservesAuthoredElevation(Component c) {
        if (c.params() != null) {
            if (Boolean.TRUE.equals(c.params().get("stilts"))
                    || Boolean.TRUE.equals(c.params().get("floating"))
                    || Boolean.TRUE.equals(c.params().get("raised"))) {
                return true;
            }
            Object ft = c.params().get("foundation_type");
            if (ft == null) ft = c.params().get("foundationType");
            if (ft != null) {
                String v = String.valueOf(ft).trim().toLowerCase(Locale.ROOT);
                if (v.equals("stilt") || v.equals("stilts") || v.equals("piles") || v.equals("piling")
                        || v.equals("floating") || v.equals("raised")) {
                    return true;
                }
            }
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f == null) continue;
                String lower = f.toLowerCase(Locale.ROOT);
                if (lower.contains("stilt") || lower.contains("floating") || lower.contains("raised")
                        || lower.contains("悬空") || lower.contains("架空")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Hexagonal / oculus lanterns hosted by a dome must keep their authored apex Y. */
    private static boolean isRoofLantern(Component c) {
        String id = componentId(c).toLowerCase(Locale.ROOT);
        if (id.contains("lantern") || id.contains("oculus") || id.contains("采光")
                || id.contains("light_well") || id.contains("cupola")) {
            return true;
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f == null) continue;
                String lower = f.toLowerCase(Locale.ROOT);
                if (lower.contains("lantern") || lower.contains("oculus") || lower.contains("采光")
                        || lower.contains("cupola") || lower.contains("light_well")) {
                    return true;
                }
            }
        }
        if (c.params() != null) {
            Object host = c.params().get("host_id");
            if (host == null) host = c.params().get("hostId");
            if (host != null) {
                String h = String.valueOf(host).toLowerCase(Locale.ROOT);
                if (h.contains("dome") || h.contains("roof") || h.contains("穹")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean xzOverlaps(ComponentFootprintUtil.Bounds a, ComponentFootprintUtil.Bounds b) {
        // Bounds max is exclusive; expand by 1 so flush face-adjacent curtain walls count.
        return a.minX() < b.maxX() + 1 && b.minX() < a.maxX() + 1
                && a.minZ() < b.maxZ() + 1 && b.minZ() < a.maxZ() + 1;
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
