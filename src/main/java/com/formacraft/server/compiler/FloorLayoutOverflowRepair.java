package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.generation.component.util.InteriorSpaceDefaults;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keep floor_count × floor_height inside the mass envelope without dropping storey bands
 * below {@link InteriorSpaceDefaults#minStoreyBand()} (which would leave &lt;3 air clear).
 * Prefer growing the envelope for host masses; clamp only thin satellite facades.
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
        boolean hostMass = type.startsWith("MASS") || "TOWER".equals(type) || "KEEP".equals(type);
        if (!hostMass && !"FACADE_WINDOWS".equals(type) && !"FACADE".equals(type)
                && !"ENTRANCE".equals(type) && !"STAIR_SYSTEM".equals(type)) {
            return component;
        }
        Map<String, Object> params = component.params();
        int floorCount = ComponentParamParsers.intParam(params, "floor_count", "floorCount");
        if (floorCount <= 0) return component;
        int envelope = Math.max(1, component.dimensions().height());
        int minBand = InteriorSpaceDefaults.minStoreyBand();
        int authoredFh = ComponentParamParsers.intParam(params, "floor_height", "floorHeight");
        int floorHeight = authoredFh;
        if (floorHeight <= 0) {
            floorHeight = Math.max(minBand, envelope / floorCount);
        } else if (hostMass) {
            floorHeight = Math.max(minBand, floorHeight);
        }
        long need = (long) floorHeight * floorCount;
        if (need <= envelope && floorHeight == authoredFh) {
            return component;
        }
        Map<String, Object> next = new HashMap<>(params);
        Dimensions dims = component.dimensions();
        if (hostMass && need > envelope) {
            // Grow envelope so min clear-height bands survive.
            dims = new Dimensions(dims.width(), dims.depth(), (int) Math.min(Integer.MAX_VALUE, need));
            next.put("floor_height", floorHeight);
            FormacraftMod.LOGGER.info(
                    "FloorLayoutOverflowRepair: grow envelope {} → {} for floors {}×{}",
                    envelope, dims.height(), floorCount, floorHeight);
            return new Component(component.componentType(), component.slotId(),
                    component.relativePosition(), dims, component.features(), next);
        }
        if (hostMass && need <= envelope && floorHeight != authoredFh) {
            next.put("floor_height", floorHeight);
            FormacraftMod.LOGGER.info(
                    "FloorLayoutOverflowRepair: raise floor_height {} → {} (min clear band)",
                    authoredFh, floorHeight);
            return new Component(component.componentType(), component.slotId(),
                    component.relativePosition(), dims, component.features(), next);
        }
        int clampedHeight = Math.max(hostMass ? minBand : 1, (int) Math.min(floorHeight, envelope / floorCount));
        int clampedCount = floorCount;
        if ((long) clampedHeight * clampedCount > envelope) {
            clampedCount = Math.max(1, envelope / Math.max(1, clampedHeight));
        }
        next.put("floor_height", clampedHeight);
        if (clampedCount != floorCount) {
            next.put("floor_count", clampedCount);
        }
        FormacraftMod.LOGGER.info(
                "FloorLayoutOverflowRepair: floors {}×{} → {}×{} (envelope={})",
                floorCount, authoredFh > 0 ? authoredFh : floorHeight,
                clampedCount, clampedHeight, envelope);
        return new Component(component.componentType(), component.slotId(), component.relativePosition(),
                dims, component.features(), next);
    }
}
