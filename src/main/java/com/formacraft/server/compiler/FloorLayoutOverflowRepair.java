package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.generation.component.util.InteriorSpaceDefaults;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.typology.TypologyParamResolver;
import com.formacraft.common.typology.UserConstraintContract;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.common.llm.dto.CapabilityGap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keep floor_count × floor_height inside the mass envelope without dropping storey bands
 * below {@link InteriorSpaceDefaults#minStoreyBand()} (which would leave &lt;3 air clear).
 * Prefer growing the envelope for host masses; clamp only thin satellite facades.
 * Never grow a server-sealed whole-building / storey height (stacked envelope authority).
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
        // Authored storey bands are intentional (same policy as ResolvedFloorLayout) — never
        // silently raise floor_height=3 → 4 and shift intermediate slabs off their pinned Y.
        int floorHeight = authoredFh > 0
                ? authoredFh
                : Math.max(minBand, envelope / floorCount);
        long need = (long) floorHeight * floorCount;
        if (need <= envelope && (authoredFh <= 0 || floorHeight == authoredFh)) {
            return component;
        }
        Map<String, Object> next = new HashMap<>(params);
        Dimensions dims = component.dimensions();
        boolean heightSealed = UserConstraintContract.isServerVerified(params, "height")
                || UserConstraintContract.isServerVerified(params, "towerHeight");
        if (hostMass && need > envelope && !heightSealed) {
            // Grow envelope so the authored (or inferred) bands still fit.
            dims = new Dimensions(dims.width(), dims.depth(), (int) Math.min(Integer.MAX_VALUE, need));
            if (authoredFh <= 0) {
                next.put("floor_height", floorHeight);
            }
            FormacraftMod.LOGGER.info(
                    "FloorLayoutOverflowRepair: grow envelope {} → {} for floors {}×{}",
                    envelope, dims.height(), floorCount, floorHeight);
            return new Component(component.componentType(), component.slotId(),
                    component.relativePosition(), dims, component.features(), next);
        }
        boolean hardFloor = UserConstraintContract.isServerVerified(params, "floor_height")
                || UserConstraintContract.isServerVerified(params, "floorHeight")
                || TypologyParamResolver.isUserHardConstraint(params, "floor_height", "floorHeight");
        // Sealed height + hard floor_height that cannot fit → capability gap, never silent rewrite.
        if (heightSealed && hardFloor && need > envelope) {
            AssemblyCompileDiagnostics.set(new CapabilityGap(
                    "E_STACKED_HEIGHT_UNSUPPORTED",
                    "玩家层高与整体高度冲突：floor_count×floor_height=" + need
                            + " 超过密封高度 " + envelope,
                    "FloorLayoutOverflowRepair",
                    List.of("Relax floor_height", "Increase whole-building height")));
            return component;
        }
        // Sealed stacked/player height must not be re-grown — clamp soft bands into the envelope.
        int clampedHeight = Math.max(hostMass ? minBand : 1, (int) Math.min(floorHeight, envelope / floorCount));
        int clampedCount = floorCount;
        if ((long) clampedHeight * clampedCount > envelope) {
            clampedCount = Math.max(1, envelope / Math.max(1, clampedHeight));
        }
        if (!hardFloor) {
            next.put("floor_height", clampedHeight);
        }
        if (clampedCount != floorCount) {
            next.put("floor_count", clampedCount);
        }
        FormacraftMod.LOGGER.info(
                "FloorLayoutOverflowRepair: floors {}×{} → {}×{} (envelope={} sealed={} hardFloor={})",
                floorCount, authoredFh > 0 ? authoredFh : floorHeight,
                clampedCount, hardFloor ? floorHeight : clampedHeight, envelope, heightSealed, hardFloor);
        return new Component(component.componentType(), component.slotId(), component.relativePosition(),
                dims, component.features(), next);
    }
}
