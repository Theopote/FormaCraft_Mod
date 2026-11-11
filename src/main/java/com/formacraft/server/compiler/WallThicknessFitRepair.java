package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.generation.component.util.OpenFramePolicy;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Soft-drop/shrink wall_thickness that would fill a thin envelope. */
final class WallThicknessFitRepair {
    private WallThicknessFitRepair() {}

    private static final String[] WALL_THICKNESS_KEYS = {"wall_thickness", "wallThickness"};

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        for (Component c : plan.components()) {
            Component next = repair(c);
            if (next != c) changed = true;
            out.add(next);
        }
        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
    }

    private static Component repair(Component component) {
        if (component == null || component.params() == null || component.dimensions() == null) return component;
        String type = component.componentType() == null ? "" : component.componentType().toUpperCase();
        if (!(type.startsWith("MASS") || "TOWER".equals(type) || "KEEP".equals(type) || "STRUCTURE".equals(type))) {
            return component;
        }
        if (OpenFramePolicy.isOpen(component.params())) return component;
        Integer thickness = ComponentParamParsers.intOrNull(component.params(), WALL_THICKNESS_KEYS);
        if (thickness == null || thickness <= 0) return component;
        int minSpan = Math.min(component.dimensions().width(), component.dimensions().depth());
        if (2L * thickness < minSpan) return component;
        Map<String, Object> next = new HashMap<>(component.params());
        int fit = Math.max(1, (minSpan - 1) / 2);
        if (fit < 1 || 2L * fit >= minSpan) {
            for (String key : WALL_THICKNESS_KEYS) next.remove(key);
            FormacraftMod.LOGGER.info("WallThicknessFitRepair: drop wall_thickness={} (minSpan={}) id={}",
                    thickness, minSpan, next.get("component_id"));
        } else {
            next.put("wall_thickness", fit);
            next.remove("wallThickness");
            FormacraftMod.LOGGER.info("WallThicknessFitRepair: wall_thickness {}→{} (minSpan={}) id={}",
                    thickness, fit, minSpan, next.get("component_id"));
        }
        return new Component(component.componentType(), component.slotId(),
                component.relativePosition(), component.dimensions(), component.features(), next);
    }
}
