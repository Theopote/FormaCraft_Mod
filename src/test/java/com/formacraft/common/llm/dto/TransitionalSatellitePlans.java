package com.formacraft.common.llm.dto;

import com.formacraft.common.generation.routing.PlanMetaAccess;

import java.util.HashMap;
import java.util.Map;

/**
 * Test-only: stamp transitional {@code allow_compiler_satellites=true} on plans
 * loaded from historical JSON that predate the opt-in default, without mutating
 * immutable on-disk captures or few-shot {@code llmplan_examples}.
 */
public final class TransitionalSatellitePlans {
    private TransitionalSatellitePlans() {}

    public static final String POLICY = "transitional_fixture";

    /** No-op when the plan already opts in or explicitly opts out. */
    public static LlmPlan ensureAllowIfAbsent(LlmPlan plan) {
        if (plan == null) {
            return null;
        }
        Map<String, Object> meta = plan.planMeta();
        LlmPlan withMeta = plan;
        if (meta == null || !meta.containsKey(PlanMetaAccess.ALLOW_COMPILER_SATELLITES)) {
            Map<String, Object> next = meta == null ? new HashMap<>() : new HashMap<>(meta);
            next.put(PlanMetaAccess.ALLOW_COMPILER_SATELLITES, Boolean.TRUE);
            next.putIfAbsent("compiler_satellites_policy", POLICY);
            withMeta = new LlmPlan(
                    plan.mode(),
                    plan.styleProfile(),
                    plan.anchor(),
                    plan.globalConstraints(),
                    plan.layout(),
                    plan.components(),
                    plan.genome(),
                    plan.styleAttributes(),
                    plan.proportionHints(),
                    plan.alignmentAndSymmetry(),
                    plan.targetSlotId(),
                    plan.allowedArea(),
                    plan.patch(),
                    plan.planProgram(),
                    plan.planSkeleton(),
                    plan.planStatus(),
                    plan.error(),
                    plan.capabilityGap(),
                    plan.playerFidelityNoticeZh(),
                    plan.distinguishingFeatures(),
                    plan.enrichmentGuard(),
                    next,
                    plan.designSources());
        }
        // Mirror CPC SteppedMassCueRepair so unit tests that only call ResolvedMassPart still
        // see plan-level stepped/terrace cues without mutating on-disk normalize goldens.
        return com.formacraft.server.compiler.SteppedMassCueRepair.apply(withMeta);
    }
}

