package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.generation.component.util.InteriorSpaceDefaults;
import com.formacraft.common.generation.design.DesignProvenance;
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
 * Prefer growing the envelope for host masses; clamp soft authored/inferred floor_height
 * into sealed envelopes when no {@code ground_clear_height}. Never reduce {@code floor_count}
 * (design authority) — emit capability_gap. Locked hard bands / ground_clear overflow gap.
 */
final class FloorLayoutOverflowRepair {
    private FloorLayoutOverflowRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        CapabilityGap gap = null;
        for (Component c : plan.components()) {
            RepairResult result = repair(plan, c, out.size());
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

    private static RepairResult repair(LlmPlan plan, Component component, int componentIndex) {
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
        int groundClear = ComponentParamParsers.intParam(params, "ground_clear_height", "groundClearHeight");
        // Authored storey bands are intentional (same policy as ResolvedFloorLayout) — never
        // silently raise floor_height=3 → 4 and shift intermediate slabs off their pinned Y.
        int floorHeight = authoredFh > 0
                ? authoredFh
                : Math.max(minBand, envelope / floorCount);
        // Match ResolvedFloorLayout: H = ground_clear + (N-1)*floor_height when lobby set.
        long need = groundClear > 0
                ? (long) groundClear + (long) Math.max(0, floorCount - 1) * floorHeight
                : (long) floorHeight * floorCount;
        if (need <= envelope) {
            return RepairResult.unchanged(component);
        }
        Map<String, Object> next = new HashMap<>(params);
        Dimensions dims = component.dimensions();
        boolean heightLocked = UserConstraintContract.isServerVerified(params, "height")
                || UserConstraintContract.isServerVerified(params, "towerHeight")
                || !DesignProvenance.canMutateParam(plan, params, "height")
                || !DesignProvenance.canMutateParam(plan, params, "towerHeight")
                || !DesignProvenance.canMutateParam(plan, params, "dimensions.height");
        boolean hardFloor = UserConstraintContract.isServerVerified(params, "floor_height")
                || UserConstraintContract.isServerVerified(params, "floorHeight")
                || TypologyParamResolver.isUserHardConstraint(params, "floor_height", "floorHeight")
                || !DesignProvenance.canMutateParam(plan, params, "floor_height");
        // Phase 02.3.2: never grow a locked/verified envelope. Hard storey bands → overflow gap.
        // Soft unlocked height may grow (FORMAT_REPAIR) so soft AI plans still compile.
        // Soft floor_height into a sealed envelope may still clamp.
        if (hardFloor) {
            CapabilityGap gap = overflowGap(componentIndex, floorCount, floorHeight, need, envelope);
            AssemblyCompileDiagnostics.set(gap);
            return RepairResult.gap(component, gap);
        }
        if (hostMass && !heightLocked) {
            // Soft unlocked envelope: grow so authored or inferred bands still fit.
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
        // Locked envelope + ground_clear formula that still exceeds need → gap.
        // Soft-clamping floor_height cannot satisfy ground_clear + (N-1)*H.
        if (groundClear > 0) {
            CapabilityGap gap = overflowGap(componentIndex, floorCount, floorHeight, need, envelope);
            AssemblyCompileDiagnostics.set(gap);
            FormacraftMod.LOGGER.info(
                    "FloorLayoutOverflowRepair: sealed envelope={} cannot fit ground_clear need={}",
                    envelope, need);
            return RepairResult.gap(component, gap);
        }
        // Soft authored/inferred floor_height may shrink into the sealed envelope — never reduce floor_count.
        int clampedHeight = Math.max(hostMass ? minBand : 1, (int) Math.min(floorHeight, envelope / floorCount));
        long clampedNeed = (long) clampedHeight * floorCount;
        if (clampedNeed > envelope) {
            CapabilityGap gap = (authoredFh > 0)
                    ? overflowGap(componentIndex, floorCount, clampedHeight, clampedNeed, envelope)
                    : conflictGap(componentIndex, floorCount, clampedHeight, clampedNeed, envelope);
            AssemblyCompileDiagnostics.set(gap);
            FormacraftMod.LOGGER.info(
                    "FloorLayoutOverflowRepair: refusing floor_count reduction {}×{} into sealed envelope={}",
                    floorCount, clampedHeight, envelope);
            return RepairResult.gap(component, gap);
        }
        DesignProvenance.putIfAllowed(plan, next, "floor_height", clampedHeight);
        FormacraftMod.LOGGER.info(
                "FloorLayoutOverflowRepair: floors {}×{} → {}×{} (envelope={} heightLocked={})",
                floorCount, authoredFh > 0 ? authoredFh : floorHeight,
                floorCount, clampedHeight, envelope, heightLocked);
        return RepairResult.of(new Component(component.componentType(), component.slotId(),
                component.relativePosition(), dims, component.features(), next));
    }

    private static CapabilityGap overflowGap(
            int componentIndex, int floorCount, int floorHeight, long need, int envelope
    ) {
        return new CapabilityGap(
                "E_FLOOR_LAYOUT_OVERFLOW",
                "楼层空间不足：floor_count=" + floorCount + "，floor_height=" + floorHeight
                        + "，需要 " + need + " 格，构件高度为 " + envelope + " 格（不静默抬高 envelope）。",
                "components[" + componentIndex + "]",
                List.of(
                        "Increase whole-building / storey height",
                        "Reduce floor_height or floor_count explicitly in the plan",
                        "Re-plan storey layout"));
    }

    private static CapabilityGap conflictGap(
            int componentIndex, int floorCount, int floorHeight, long need, int envelope
    ) {
        return new CapabilityGap(
                "E_FLOOR_LAYOUT_CONFLICT",
                "楼层空间不足：floor_count=" + floorCount + "，floor_height=" + floorHeight
                        + "，需要 " + need + " 格，构件高度为 " + envelope + " 格。",
                "components[" + componentIndex + "]",
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
