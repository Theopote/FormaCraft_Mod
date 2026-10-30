package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Drop invalid courtyard_ratio=0 / out-of-range values that LLM often emits with
 * non-courtyard masses, before MassDesignValidator hard-fails.
 */
final class CourtyardRatioRepair {
    private static final String[] RATIO_KEYS = {
            "courtyard_ratio", "courtyardRatio", "court_ratio"
    };

    private CourtyardRatioRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        for (Component c : plan.components()) {
            Component repaired = repair(c);
            if (repaired != c) changed = true;
            out.add(repaired);
        }
        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
    }

    private static Component repair(Component component) {
        if (component == null || component.params() == null || component.params().isEmpty()) {
            return component;
        }
        Map<String, Object> params = component.params();
        Map<String, Object> next = null;
        for (String key : RATIO_KEYS) {
            if (!params.containsKey(key) || params.get(key) == null) continue;
            Double ratio = parse(params.get(key));
            if (ratio != null && ratio > 0.0 && ratio < 1.0) continue;
            if (next == null) next = new HashMap<>(params);
            next.remove(key);
            FormacraftMod.LOGGER.info(
                    "CourtyardRatioRepair: drop invalid {}={} (component={})",
                    key, params.get(key), component.componentType());
        }
        // void_ratio=0 is a solid-mass marker — leave it.
        if (next == null) return component;
        // If plan_type was courtyard only because of the bad ratio, clear it.
        Object planType = next.get("plan_type");
        if (planType == null) planType = next.get("planType");
        if (planType != null && "courtyard".equalsIgnoreCase(String.valueOf(planType))
                && !next.containsKey("courtyard_ratio") && !next.containsKey("courtyardRatio")
                && !next.containsKey("court_ratio")) {
            next.put("plan_type", "none");
        }
        return new Component(component.componentType(), component.slotId(),
                component.relativePosition(), component.dimensions(), component.features(), next);
    }

    private static Double parse(Object raw) {
        if (raw instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(raw).trim().toLowerCase(Locale.ROOT));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
