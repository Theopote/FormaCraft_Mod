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
 * Normalize hexagon / octagon plan tokens onto native footprint shapes.
 * Hexagon and octagon are never substituted for each other.
 * When only {@code plan_type} carries a polygon token, clear it to {@code none} so the
 * pattern catalog does not double-cut while {@link ResolvedFootprint} owns the silhouette.
 */
final class FootprintShapeRemapRepair {
    private static final Set<String> HEXAGON_TOKENS = Set.of(
            "hexagon", "hexagonal", "hex", "6gon", "六角", "六角形");
    private static final Set<String> OCTAGON_TOKENS = Set.of(
            "octagon", "octagonal", "regular_octagon", "8gon", "八角", "八角形");

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
        boolean shapeOct = shapeRaw.isPresent() && isOctagonToken(shapeRaw.get());
        boolean planOct = planRaw.isPresent() && isOctagonToken(planRaw.get());
        boolean shapeRing = shapeRaw.isPresent() && isColonnadeRingToken(shapeRaw.get());
        boolean shapeSteps = shapeRaw.isPresent() && isStepsToken(shapeRaw.get());
        boolean shapeDome = shapeRaw.isPresent() && isDomeToken(shapeRaw.get());
        boolean shapeTrapezoid = shapeRaw.isPresent() && isTrapezoidToken(shapeRaw.get());
        if (!shapeHex && !planHex && !shapeOct && !planOct
                && !shapeRing && !shapeSteps && !shapeDome && !shapeTrapezoid) {
            return component;
        }
        Map<String, Object> next = new HashMap<>(params);
        if (shapeTrapezoid) {
            for (String key : FootprintShapeCatalog.SHAPE_KEYS) {
                if (next.containsKey(key) && isTrapezoidToken(String.valueOf(next.get(key)))) {
                    next.put(key, "rectangle");
                }
            }
            Object planType = next.get("plan_type");
            if (planType == null) planType = next.get("planType");
            if (planType == null || String.valueOf(planType).isBlank()
                    || "none".equalsIgnoreCase(String.valueOf(planType))) {
                next.put("plan_type", "cut_corners");
                next.putIfAbsent("corner_cut", 2);
            }
            FormacraftMod.LOGGER.info(
                    "FootprintShapeRemapRepair: normalize trapezoid → shape=rectangle plan_type={} (component={})",
                    next.get("plan_type"), component.componentType());
            return new Component(
                    component.componentType(),
                    component.slotId(),
                    component.relativePosition(),
                    component.dimensions(),
                    component.features(),
                    next);
        }
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
        // Prefer hexagon when both tokens appear — never silently map hex → octagon.
        if (shapeHex || planHex) {
            return normalizePolygon(component, next, "hexagon", shapeRaw, planRaw, true);
        }
        return normalizePolygon(component, next, "octagon", shapeRaw, planRaw, false);
    }

    private static Component normalizePolygon(
            Component component,
            Map<String, Object> next,
            String shapeToken,
            Optional<String> shapeRaw,
            Optional<String> planRaw,
            boolean hexagon
    ) {
        boolean wroteShape = false;
        for (String key : FootprintShapeCatalog.SHAPE_KEYS) {
            if (next.containsKey(key)) {
                next.put(key, shapeToken);
                wroteShape = true;
            }
        }
        if (!wroteShape) {
            next.put("shape", shapeToken);
        }
        next.put("sides", hexagon ? 6 : 8);
        // Always clear polygon tokens from plan_type so ResolvedFootprint owns the silhouette.
        for (String key : PlanPatternCatalog.PLAN_TYPE_KEYS) {
            if (!next.containsKey(key)) continue;
            String v = String.valueOf(next.get(key));
            if (isHexagonToken(v) || isOctagonToken(v)) {
                next.put(key, "none");
            }
        }
        // Drop legacy corner_cut when native polygon owns the silhouette.
        next.remove("corner_cut");
        next.remove("cornerCut");
        FormacraftMod.LOGGER.info(
                "FootprintShapeRemapRepair: normalize {} → shape={} (component={} shape={} plan_type={})",
                shapeToken,
                shapeToken,
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

    private static boolean isTrapezoidToken(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return v.equals("trapezoid") || v.equals("trapezium") || v.equals("trapezoidal")
                || v.equals("梯形") || v.contains("trapezoid") || v.contains("trapezium");
    }

    private static boolean isHexagonToken(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (HEXAGON_TOKENS.contains(v)) return true;
        return v.contains("hexagon") || v.contains("hexagonal") || raw.contains("六角");
    }

    private static boolean isOctagonToken(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (OCTAGON_TOKENS.contains(v)) return true;
        return v.contains("octagon") || v.contains("octagonal") || raw.contains("八角");
    }
}
