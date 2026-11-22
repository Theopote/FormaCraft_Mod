package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Snap floating primary masses (apartments / halls with y&gt;0 and no support) down onto
 * foundations or y=0 so buildings land instead of hovering above terrain.
 * <p>
 * Independent multi-building plans are snapped per mass. Intentionally stacked masses
 * (sitting on another mass / stilts / floating markers) keep authored elevation.
 * Hosted plinths ({@code host_id} → this mass) are satellites, not landing pads — they
 * must not pull a raised body down. Modest podiums without external support also keep Y.
 */
final class FloatingMassSnapRepair {
    /**
     * Without an external support surface, only unambiguous mid-air elevations snap to y=0.
     * Smaller Y gaps are treated as authored podiums / slot lifts (design authority).
     */
    private static final int MIN_UNAMBIGUOUS_FLOAT_Y = 6;

    private FloatingMassSnapRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        List<Component> components = new ArrayList<>(plan.components());
        boolean changed = false;
        for (int i = 0; i < components.size(); i++) {
            Component mass = components.get(i);
            if (mass == null || mass.relativePosition() == null || mass.dimensions() == null) continue;
            if (!isPrimaryMass(mass)) continue;
            int y = mass.relativePosition().y();
            if (y <= 0) continue;
            if (preservesAuthoredElevation(mass)) continue;
            String massId = componentId(mass);
            // Raised halls/towers keep an authored plinth under them (host_id → this mass).
            // That plinth is not a reason to flatten the body onto terrain.
            if (hasHostedPlinth(massId, components)) continue;
            int supportTop = supportTopY(mass, massId, components);
            if (y <= supportTop) continue;
            // Bare ground: only clear floating (LLM mid-air) — not modest authored podiums.
            if (supportTop <= 0 && y < MIN_UNAMBIGUOUS_FLOAT_Y) continue;
            int dy = supportTop - y;
            FormacraftMod.LOGGER.info(
                    "FloatingMassSnapRepair: {} y={} → {} (supportTop={})",
                    massId, y, supportTop, supportTop);
            components.set(i, shift(mass, dy));
            // Full authored host tree by index (incl. host_id attachments without component_id).
            var descendants = HostAttachmentTree.descendantIndices(massId, components);
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
        return changed ? PlanComponentRewrite.withComponents(plan, components) : plan;
    }

    private static boolean isPrimaryMass(Component c) {
        String t = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
        return "MASS_MAIN".equals(t) || "MAIN_MASS".equals(t);
    }

    private static int supportTopY(Component mass, String massId, List<Component> components) {
        var massBounds = ComponentFootprintUtil.bounds(mass);
        if (massBounds == null) return 0;
        int massY = mass.relativePosition().y();
        int best = 0;
        for (Component c : components) {
            if (c == null || c == mass || c.relativePosition() == null || c.dimensions() == null) continue;
            if (!isSupport(c)) continue;
            // Satellites that declare this mass as host are plinths/attachments, not landing pads.
            if (hostsMass(c, massId)) continue;
            var b = ComponentFootprintUtil.bounds(c);
            if (b == null || !xzOverlaps(massBounds, b)) continue;
            int top = c.relativePosition().y() + Math.max(1, c.dimensions().height());
            // Only surfaces that meet the mass from below (or flush) count as support.
            if (top > massY) continue;
            best = Math.max(best, top);
        }
        return best;
    }

    private static boolean hostsMass(Component candidate, String massId) {
        if (massId == null || candidate.params() == null) return false;
        Object host = candidate.params().get("host_id");
        if (host == null) host = candidate.params().get("hostId");
        return host != null && massId.equals(String.valueOf(host));
    }

    /** Authored FOUNDATION/BASE satellites imply a deliberate raised body, not a float bug. */
    private static boolean hasHostedPlinth(String massId, List<Component> components) {
        if (massId == null) return false;
        for (Component c : components) {
            if (c == null) continue;
            String t = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
            if (!"FOUNDATION".equals(t) && !"BASE".equals(t)) continue;
            if (hostsMass(c, massId)) return true;
        }
        return false;
    }

    private static boolean isSupport(Component c) {
        String t = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
        if ("FOUNDATION".equals(t) || "TERRACE".equals(t) || "BASE".equals(t)
                || "KEEP".equals(t) || "WALL".equals(t)
                || "MASS_MAIN".equals(t) || "MAIN_MASS".equals(t) || "MASS_SECONDARY".equals(t)) {
            return true;
        }
        // Authored pier posts / trunks under a raised deck (stilts, treehouses, water platforms).
        if ("PRIMITIVE".equals(t) || "STRUCTURE".equals(t) || "DECOR_DETAIL".equals(t)
                || "DECOR".equals(t) || "DETAIL".equals(t)) {
            if (com.formacraft.common.generation.component.util.StructuralSupportPolicy.isColumn(c.params())) {
                return true;
            }
            String id = componentId(c).toLowerCase(Locale.ROOT);
            if (id.startsWith("post_") || id.startsWith("access_post")
                    || id.contains("stilt") || id.contains("piling") || id.contains("_pile")
                    || id.contains("trunk") || id.contains("stem") || id.contains("column")) {
                return true;
            }
        }
        return false;
    }

    /** Stilts / floating / raised markers keep authored Y (design authority). */
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
                if (Set.of("stilt", "stilts", "piles", "piling", "floating", "raised").contains(v)) {
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

    private static boolean xzOverlaps(ComponentFootprintUtil.Bounds a, ComponentFootprintUtil.Bounds b) {
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
