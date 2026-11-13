package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.generation.component.util.MassComponentKinds;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Inject host_id for same-slot stacked upper masses missing a bind.
 * Hosted plates (bridge/terrace/interior decks) keep authored host/target ids —
 * binding a free bank landing onto a bridge plate falsely creates
 * {@code E_HOST_ID_UNRESOLVED} because plates are not interior geometry hosts.
 */
final class StackedMassHostBindRepair {
    private StackedMassHostBindRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().size() < 2) return plan;
        List<Component> masses = new ArrayList<>();
        for (Component c : plan.components()) {
            // Circulation shells only — never treat plates as stacked tiers or supports.
            if (c != null && MassComponentKinds.isCirculationMass(c)
                    && c.relativePosition() != null && c.params() != null
                    && c.params().get("component_id") != null) {
                masses.add(c);
            }
        }
        if (masses.size() < 2) return plan;
        Map<String, String> bind = new HashMap<>();
        for (Component upper : masses) {
            Object existing = upper.params().get("host_id");
            if (existing == null) existing = upper.params().get("hostId");
            if (existing != null && !String.valueOf(existing).isBlank()
                    && !"null".equalsIgnoreCase(String.valueOf(existing))) continue;
            Component lower = findSupport(upper, masses);
            if (lower == null) continue;
            String lowerId = String.valueOf(lower.params().get("component_id"));
            String upperId = String.valueOf(upper.params().get("component_id"));
            if (!lowerId.equals(upperId)) bind.put(upperId, lowerId);
        }
        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        for (Component c : plan.components()) {
            if (c == null || c.params() == null || c.params().get("component_id") == null) {
                out.add(c);
                continue;
            }
            String id = String.valueOf(c.params().get("component_id"));
            String host = bind.get(id);
            Map<String, Object> params = null;
            if (host != null) {
                params = new HashMap<>(c.params());
                params.put("host_id", host);
                params.putIfAbsent("allow_patch_overwrite", true);
                FormacraftMod.LOGGER.info("StackedMassHostBindRepair: bind {} → host_id={}", id, host);
                changed = true;
            }
            // Hosted upper floor-plate shells must not replay the host's floor_count — a second
            // local slab (e.g. world y=7 on floor_plate_upper@y=4) blocks façade windows.
            if (clampHostedFloorPlateStoreys(c, params != null ? params : c.params())) {
                if (params == null) params = new HashMap<>(c.params());
                params.put("floor_count", 1);
                FormacraftMod.LOGGER.info(
                        "StackedMassHostBindRepair: clamp floor_count→1 on hosted floor plate {}", id);
                changed = true;
            }
            if (params != null) {
                out.add(new Component(c.componentType(), c.slotId(), c.relativePosition(),
                        c.dimensions(), c.features(), params));
            } else {
                out.add(c);
            }
        }
        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
    }

    /** True when this hosted secondary is a floor-plate shell with duplicated storey bands. */
    private static boolean clampHostedFloorPlateStoreys(Component c, Map<String, Object> params) {
        if (c == null || params == null || !"MASS_SECONDARY".equalsIgnoreCase(c.componentType())) {
            return false;
        }
        Object host = params.get("host_id");
        if (host == null) host = params.get("hostId");
        if (host == null || String.valueOf(host).isBlank()
                || "null".equalsIgnoreCase(String.valueOf(host))) {
            return false;
        }
        Object rawCount = params.get("floor_count");
        if (rawCount == null) rawCount = params.get("floorCount");
        int floors = rawCount instanceof Number n ? n.intValue() : 0;
        if (floors <= 1) return false;
        String id = String.valueOf(params.get("component_id")).toLowerCase(Locale.ROOT);
        return id.contains("floor_plate") || id.contains("plate_upper")
                || id.contains("upper_plate") || id.contains("upper_floor");
    }

    private static Component findSupport(Component upper, List<Component> masses) {
        ComponentFootprintUtil.Bounds ub = ComponentFootprintUtil.bounds(upper);
        if (ub == null) return null;
        Component best = null;
        int bestTop = Integer.MIN_VALUE;
        for (Component lower : masses) {
            if (lower == upper) continue;
            if (upper.slotId() != null && lower.slotId() != null && !upper.slotId().equals(lower.slotId())) continue;
            ComponentFootprintUtil.Bounds lb = ComponentFootprintUtil.bounds(lower);
            if (lb == null) continue;
            if (ub.minY() < lb.maxY() - 2) continue;
            if (ub.minY() > lb.maxY() + 4) continue;
            boolean xzOverlap = ub.minX() < lb.maxX() && ub.maxX() > lb.minX()
                    && ub.minZ() < lb.maxZ() && ub.maxZ() > lb.minZ();
            boolean nestedLoose = ub.minX() >= lb.minX() - 3 && ub.maxX() <= lb.maxX() + 3
                    && ub.minZ() >= lb.minZ() - 3 && ub.maxZ() <= lb.maxZ() + 3;
            if (!xzOverlap && !nestedLoose) continue;
            if (lb.maxY() > bestTop) {
                bestTop = lb.maxY();
                best = lower;
            }
        }
        if (best == null) {
            String uid = String.valueOf(upper.params().get("component_id")).toLowerCase(Locale.ROOT);
            for (Component lower : masses) {
                if (lower == upper) continue;
                String lid = String.valueOf(lower.params().get("component_id")).toLowerCase(Locale.ROOT);
                if ((uid.contains("belfry") || uid.contains("upper") || uid.contains("jetty")
                        || uid.contains("cap") || uid.contains("attic"))
                        && (lid.contains("shaft") || lid.contains("ground") || lid.contains("main")
                        || lid.contains("body") || lid.contains("tower"))) {
                    ComponentFootprintUtil.Bounds lb = ComponentFootprintUtil.bounds(lower);
                    if (lb != null && ub.minY() >= lb.minY()) return lower;
                }
            }
        }
        return best;
    }
}
