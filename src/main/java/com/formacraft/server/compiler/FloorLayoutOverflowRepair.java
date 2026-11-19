package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.generation.component.util.InteriorSpaceDefaults;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.typology.TypologyParamResolver;
import com.formacraft.common.typology.UserConstraintContract;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keep floor_count × floor_height inside the mass envelope without dropping storey bands
 * below {@link InteriorSpaceDefaults#minStoreyBand()} (which would leave &lt;3 air clear).
 * Prefer growing the envelope for host masses; clamp only soft floor_height into sealed
 * envelopes. Never reduce {@code floor_count} (design authority) — emit capability_gap.
 * Never grow a server-sealed whole-building / storey height (stacked envelope authority).
 */
final class FloorLayoutOverflowRepair {
    private FloorLayoutOverflowRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        CapabilityGap gap = null;
        for (Component c : plan.components()) {
            RepairResult result = repair(c);
            if (result.component() != c) changed = true;
            out.add(result.component());
            if (result.gap() != null) {
                gap = result.gap();
            }
        }
        LlmPlan next = changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
        if (gap != null) {
            return PlanComponentRewrite.withCapabilityGap(next, gap);
        }
        return next;
    }

    private static RepairResult repair(Component component) {
        if (component == null || component.params() == null || component.dimensions() == null) {
            return RepairResult.unchanged(component);
        }
        String type = component.componentType() == null ? "" : component.componentType().toUpperCase();
        boolean hostMass = type.startsWith("MASS") || "TOWER".equals(type) || "KEEP".equals(type);
        if (!hostMass && !"FACADE_WINDOWS".equals(type) && !"FACADE".equals(type)
                && !"ENTRANCE".equals(type) && !"STAIR_SYSTEM".equals(type)) {
            return RepairResult.unchanged(component);
        }
        Map<String, Object> params = component.params();
        int floorCount = ComponentParamParsers.intParam(params, "floor_count", "floorCount");
        if (floorCount <= 0) return RepairResult.unchanged(component);
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
            return RepairResult.unchanged(component);
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
            return RepairResult.of(new Component(component.componentType(), component.slotId(),
                    component.relativePosition(), dims, component.features(), next));
        }
        boolean hardFloor = UserConstraintContract.isServerVerified(params, "floor_height")
                || UserConstraintContract.isServerVerified(params, "floorHeight")
                || TypologyParamResolver.isUserHardConstraint(params, "floor_height", "floorHeight");
        // Sealed height + hard floor_height that cannot fit → capability gap, never silent rewrite.
        if (heightSealed && hardFloor && need > envelope) {
            CapabilityGap gap = conflictGap(floorCount, floorHeight, need, envelope);
            AssemblyCompileDiagnostics.set(gap);
            return RepairResult.gap(component, gap);
        }
        // Soft floor_height may shrink into the sealed envelope — never reduce floor_count.
        int clampedHeight = Math.max(hostMass ? minBand : 1, (int) Math.min(floorHeight, envelope / floorCount));
        if ((long) clampedHeight * floorCount > envelope) {
            CapabilityGap gap = conflictGap(floorCount, clampedHeight, (long) clampedHeight * floorCount, envelope);
            AssemblyCompileDiagnostics.set(gap);
            FormacraftMod.LOGGER.info(
                    "FloorLayoutOverflowRepair: refusing floor_count reduction {}×{} into sealed envelope={}",
                    floorCount, clampedHeight, envelope);
            return RepairResult.gap(component, gap);
        }
        if (!hardFloor && clampedHeight != floorHeight) {
            next.put("floor_height", clampedHeight);
        } else if (hardFloor && (long) floorHeight * floorCount > envelope) {
            CapabilityGap gap = conflictGap(floorCount, floorHeight, need, envelope);
            AssemblyCompileDiagnostics.set(gap);
            return RepairResult.gap(component, gap);
        } else if (clampedHeight == floorHeight && need <= envelope) {
            return RepairResult.unchanged(component);
        }
        FormacraftMod.LOGGER.info(
                "FloorLayoutOverflowRepair: floors {}×{} → {}×{} (envelope={} sealed={} hardFloor={})",
                floorCount, authoredFh > 0 ? authoredFh : floorHeight,
                floorCount, hardFloor ? floorHeight : clampedHeight, envelope, heightSealed, hardFloor);
        return RepairResult.of(new Component(component.componentType(), component.slotId(),
                component.relativePosition(), dims, component.features(), next));
    }

    private static CapabilityGap conflictGap(int floorCount, int floorHeight, long need, int envelope) {
        return new CapabilityGap(
                "E_FLOOR_LAYOUT_CONFLICT",
                "层数与密封高度冲突：floor_count×floor_height=" + need
                        + " 超过密封高度 " + envelope + "（拒绝静默减少 floor_count=" + floorCount + "）",
                "FloorLayoutOverflowRepair",
                List.of(
                        "Increase whole-building / storey height",
                        "Reduce floor_height or floor_count explicitly in the plan",
                        "Re-plan storey layout"));
    }

    private record RepairResult(Component component, CapabilityGap gap) {
        static RepairResult unchanged(Component c) {
            return new RepairResult(c, null);
        }

        static RepairResult of(Component c) {
            return new RepairResult(c, null);
        }

        static RepairResult gap(Component c, CapabilityGap gap) {
            return new RepairResult(c, gap);
        }
    }
}
