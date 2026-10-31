package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.FootprintShapeCatalog;
import com.formacraft.common.generation.component.util.PlanPatternCatalog;
import com.formacraft.common.generation.component.util.ResolvedFootprint;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Soft-repair incompatible circle + non-courtyard plan_type pairs before authored validation.
 * Cut-corners / octagon: keep the plan cut and convert shape to rectangle.
 * Cross / L: prefer keeping an explicit circle (drop plan_type) so round houses still generate.
 */
final class FootprintCombinationRepair {
    private FootprintCombinationRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return plan;
        }
        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        for (Component c : plan.components()) {
            Component repaired = repair(c);
            if (repaired != c) {
                changed = true;
            }
            out.add(repaired);
        }
        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
    }

    private static Component repair(Component component) {
        if (component == null || component.params() == null || component.params().isEmpty()) {
            return component;
        }
        Map<String, Object> params = component.params();
        Optional<String> shapeRaw = FootprintShapeCatalog.extractExplicit(params);
        Optional<String> planRaw = PlanPatternCatalog.extractExplicit(params);
        if (shapeRaw.isEmpty() || planRaw.isEmpty()) {
            return component;
        }
        if (!FootprintShapeCatalog.isSupportedExplicit(shapeRaw.get())
                || !PlanPatternCatalog.isSupportedExplicit(planRaw.get())) {
            return component;
        }
        ResolvedFootprint.FootprintShape shape = FootprintShapeCatalog.parseSupported(shapeRaw.get()).orElse(null);
        ResolvedFootprint.PlanPattern pattern = PlanPatternCatalog.parseSupported(planRaw.get()).orElse(null);
        if (shape != ResolvedFootprint.FootprintShape.CIRCLE
                || pattern == null
                || !PlanPatternCatalog.requiresNonCircularBase(pattern)) {
            return component;
        }
        Map<String, Object> next = new HashMap<>(params);
        if (pattern == ResolvedFootprint.PlanPattern.CUT_CORNERS) {
            for (String key : FootprintShapeCatalog.SHAPE_KEYS) {
                if (next.containsKey(key)) {
                    next.put(key, "rectangle");
                }
            }
            if (!next.containsKey("shape")) {
                next.put("shape", "rectangle");
            }
            FormacraftMod.LOGGER.info(
                    "FootprintCombinationRepair: shape=circle incompatible with plan_type={} → shape=rectangle (component={})",
                    planRaw.get().toLowerCase(Locale.ROOT),
                    component.componentType());
        } else {
            for (String key : PlanPatternCatalog.PLAN_TYPE_KEYS) {
                if (next.containsKey(key)) {
                    next.put(key, "none");
                }
            }
            FormacraftMod.LOGGER.info(
                    "FootprintCombinationRepair: shape=circle incompatible with plan_type={} → plan_type=none (component={})",
                    planRaw.get().toLowerCase(Locale.ROOT),
                    component.componentType());
        }
        return new Component(
                component.componentType(),
                component.slotId(),
                component.relativePosition(),
                component.dimensions(),
                component.features(),
                next);
    }
}
