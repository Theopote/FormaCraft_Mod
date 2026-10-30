package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Clamp floor_height so floor_count × floor_height fits the mass envelope instead of
 * hard-failing with E_FLOOR_LAYOUT_OVERFLOW.
 */
final class FloorLayoutOverflowRepair {
    private FloorLayoutOverflowRepair() {}

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
        if (component == null || component.params() == null || component.dimensions() == null) {
            return component;
        }
        String type = component.componentType() == null ? "" : component.componentType().toUpperCase();
        if (!type.startsWith("MASS") && !"TOWER".equals(type) && !"KEEP".equals(type)
                && !"FACADE_WINDOWS".equals(type) && !"FACADE".equals(type)
                && !"ENTRANCE".equals(type) && !"STAIR_SYSTEM".equals(type)) {
            return component;
        }
        Map<String, Object> params = component.params();
        int floorCount = ComponentParamParsers.intParam(params, "floor_count", "floorCount");
        if (floorCount <= 0) return component;
        int envelope = Math.max(1, component.dimensions().height());
        int floorHeight = ComponentParamParsers.intParam(params, "floor_height", "floorHeight");
        if (floorHeight <= 0) {
            floorHeight = Math.max(1, envelope / floorCount);
        }
        if ((long) floorHeight * floorCount <= envelope) return component;
        int clampedHeight = Math.max(1, envelope / floorCount);
        int clampedCount = floorCount;
        if ((long) clampedHeight * clampedCount > envelope) {
            clampedCount = Math.max(1, envelope / Math.max(1, clampedHeight));
        }
        Map<String, Object> next = new HashMap<>(params);
        next.put("floor_height", clampedHeight);
        if (clampedCount != floorCount) {
            next.put("floor_count", clampedCount);
        }
        FormacraftMod.LOGGER.info(
                "FloorLayoutOverflowRepair: floors {}×{} → {}×{} (envelope={})",
                floorCount, floorHeight, clampedCount, clampedHeight, envelope);
        return new Component(component.componentType(), component.slotId(), component.relativePosition(),
                component.dimensions(), component.features(), next);
    }
}
