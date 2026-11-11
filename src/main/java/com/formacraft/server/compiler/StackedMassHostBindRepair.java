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

/** Inject host_id for same-slot stacked upper masses missing a bind. */
final class StackedMassHostBindRepair {
    private StackedMassHostBindRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().size() < 2) return plan;
        List<Component> masses = new ArrayList<>();
        for (Component c : plan.components()) {
            if (c != null && MassComponentKinds.isHostMass(c) && c.dimensions() != null
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
        if (bind.isEmpty()) return plan;
        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        for (Component c : plan.components()) {
            if (c == null || c.params() == null || c.params().get("component_id") == null) {
                out.add(c);
                continue;
            }
            String id = String.valueOf(c.params().get("component_id"));
            String host = bind.get(id);
            if (host == null) {
                out.add(c);
                continue;
            }
            Map<String, Object> params = new HashMap<>(c.params());
            params.put("host_id", host);
            params.putIfAbsent("allow_patch_overwrite", true);
            FormacraftMod.LOGGER.info("StackedMassHostBindRepair: bind {} → host_id={}", id, host);
            out.add(new Component(c.componentType(), c.slotId(), c.relativePosition(),
                    c.dimensions(), c.features(), params));
            changed = true;
        }
        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
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
