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
import java.util.Set;

/**
 * Normalize hexagon plan tokens onto native {@code shape=hexagon}.
 * Never substitutes octagon — six- and eight-sided pavilions are different forms.
 * When only {@code plan_type} carries a hexagon token, clear it to {@code none} so the
 * pattern catalog does not hard-fail while {@link ResolvedFootprint} cuts a hex base.
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
        boolean shapeRing = shapeRaw.isPresent() && isColonnadeRingToken(shapeRaw.get());
        boolean shapeSteps = shapeRaw.isPresent() && isStepsToken(shapeRaw.get());
        boolean shapeDome = shapeRaw.isPresent() && isDomeToken(shapeRaw.get());
        if (!shapeHex && !planHex && !shapeRing && !shapeSteps && !shapeDome) {
            return component;
        }
        Map<String, Object> next = new HashMap<>(params);
        if (shapeSteps) {
            for (String key : FootprintShapeCatalog.SHAPE_KEYS) {
                if (next.containsKey(key) && isStepsToken(String.valueOf(next.get(key)))) {
                    next.put(key, "rectangle");
                }
            }
            FormacraftMod.LOGGER.info(
                    "FootprintShapeRemapRepair: normalize steps/stair → shape=rectangle (component={})",
                    component.componentType());
            return new Component(
                    component.componentType(),
                    component.slotId(),
                    component.relativePosition(),
                    component.dimensions(),
                    component.features(),
                    next);
        }
        if (shapeDome) {
            for (String key : FootprintShapeCatalog.SHAPE_KEYS) {
                if (next.containsKey(key) && isDomeToken(String.valueOf(next.get(key)))) {
                    next.put(key, "circle");
                }
            }
            FormacraftMod.LOGGER.info(
                    "FootprintShapeRemapRepair: normalize hemisphere/dome → shape=circle (component={})",
                    component.componentType());
            return new Component(
                    component.componentType(),
                    component.slotId(),
                    component.relativePosition(),
                    component.dimensions(),
                    component.features(),
                    next);
        }
        if (shapeRing) {
            for (String key : FootprintShapeCatalog.SHAPE_KEYS) {
                if (next.containsKey(key) && isColonnadeRingToken(String.valueOf(next.get(key)))) {
                    next.put(key, "circle");
                }
            }
            // Hollow the annulus so peristyle rings are not solid cylinders.
            boolean hasPlan = false;
            for (String key : PlanPatternCatalog.PLAN_TYPE_KEYS) {
                if (next.containsKey(key)) {
                    next.put(key, "ring");
                    hasPlan = true;
                }
            }
            if (!hasPlan) {
                next.put("plan_type", "ring");
            }
            FormacraftMod.LOGGER.info(
                    "FootprintShapeRemapRepair: normalize colonnade_ring → shape=circle plan_type=ring (component={})",
                    component.componentType());
            return new Component(
                    component.componentType(),
                    component.slotId(),
                    component.relativePosition(),
                    component.dimensions(),
                    component.features(),
                    next);
        }
        boolean wroteShape = false;
        for (String key : FootprintShapeCatalog.SHAPE_KEYS) {
            if (next.containsKey(key)) {
                next.put(key, "hexagon");
                wroteShape = true;
            }
        }
        if (!wroteShape) {
            next.put("shape", "hexagon");
        }
        // plan_type=hexagon is not a cut-corners pattern — clear to none so native HEXAGON shape owns the cut.
        if (planHex) {
            for (String key : PlanPatternCatalog.PLAN_TYPE_KEYS) {
                if (next.containsKey(key) && isHexagonToken(String.valueOf(next.get(key)))) {
                    next.put(key, "none");
                }
            }
        }
        FormacraftMod.LOGGER.info(
                "FootprintShapeRemapRepair: normalize hexagon → shape=hexagon (component={} shape={} plan_type={})",
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

    private static boolean isColonnadeRingToken(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return v.equals("colonnade_ring") || v.equals("column_ring") || v.equals("peristyle_ring")
                || v.equals("annulus") || v.equals("ring");
    }

    /** Processional stair / crepidoma — not a footprint cut; keep rectangular AABB. */
    private static boolean isStepsToken(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return v.equals("steps") || v.equals("step") || v.equals("stair") || v.equals("stairs")
                || v.equals("staircase") || v.equals("stepped") || v.equals("ramp")
                || v.equals("crepidoma") || v.contains("processional_stair");
    }

    private static boolean isDomeToken(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return v.equals("hemisphere") || v.equals("hemispherical") || v.equals("dome")
                || v.equals("半圆") || v.equals("半球");
    }

    private static boolean isHexagonToken(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (HEXAGON_TOKENS.contains(v)) return true;
        return v.contains("hexagon") || v.contains("hexagonal") || raw.contains("六角");
    }
}
