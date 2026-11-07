package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.FootprintShapeCatalog;
import com.formacraft.common.generation.component.util.PlanPatternCatalog;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Soft-remap LLM footprint tokens that the runtime cannot cut exactly.
 * Hexagon / hexagonal → octagon AABB + plan_type=octagon (cut-corners), before
 * {@link MassDesignValidator} hard-fails {@code E_FOOTPRINT_SHAPE_UNSUPPORTED}.
 */
final class FootprintShapeRemapRepair {
    private static final Set<String> HEXAGON_TOKENS = Set.of(
            "hexagon", "hexagonal", "hex", "6gon", "六角", "六角形");

    private FootprintShapeRemapRepair() {}

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
        boolean shapeHex = shapeRaw.isPresent() && isHexagonToken(shapeRaw.get());
        boolean planHex = planRaw.isPresent() && isHexagonToken(planRaw.get());
        if (!shapeHex && !planHex) {
            return component;
        }
        Map<String, Object> next = new HashMap<>(params);
        if (shapeHex) {
            boolean wroteShape = false;
            for (String key : FootprintShapeCatalog.SHAPE_KEYS) {
                if (next.containsKey(key)) {
                    next.put(key, "octagon");
                    wroteShape = true;
                }
            }
            if (!wroteShape) {
                next.put("shape", "octagon");
            }
        }
        // Ensure cut-corners plan so the octagon silhouette is actually carved.
        if (planHex || shapeHex) {
            boolean wrotePlan = false;
            for (String key : PlanPatternCatalog.PLAN_TYPE_KEYS) {
                if (next.containsKey(key)) {
                    next.put(key, "octagon");
                    wrotePlan = true;
                }
            }
            if (!wrotePlan) {
                next.put("plan_type", "octagon");
            }
        }
        FormacraftMod.LOGGER.info(
                "FootprintShapeRemapRepair: hexagon → octagon (component={} shape={} plan_type={})",
                component.componentType(),
                shapeRaw.orElse("-"),
                planRaw.orElse("-"));
        return new Component(
                component.componentType(),
                component.slotId(),
                component.relativePosition(),
                component.dimensions(),
                component.features(),
                next);
    }

    private static boolean isHexagonToken(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (HEXAGON_TOKENS.contains(v)) return true;
        return v.contains("hexagon") || v.contains("hexagonal") || raw.contains("六角");
    }
}
