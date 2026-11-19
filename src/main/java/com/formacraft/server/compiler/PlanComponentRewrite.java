package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;

import java.util.List;

/** Shared LlmPlan reconstruction when only components[] or layout change. */
final class PlanComponentRewrite {
    private PlanComponentRewrite() {}

    static LlmPlan withComponents(LlmPlan plan, List<Component> components) {
        return new LlmPlan(
                plan.mode(), plan.styleProfile(), plan.anchor(), plan.globalConstraints(), plan.layout(),
                components, plan.genome(), plan.styleAttributes(), plan.proportionHints(),
                plan.alignmentAndSymmetry(), plan.targetSlotId(), plan.allowedArea(), plan.patch(),
                plan.planProgram(), plan.planSkeleton(), plan.planStatus(), plan.error(),
                plan.capabilityGap(), plan.playerFidelityNoticeZh(), plan.distinguishingFeatures(),
                plan.enrichmentGuard(), plan.planMeta(), plan.designSources());
    }

    static LlmPlan withLayout(LlmPlan plan, Layout layout) {
        return new LlmPlan(
                plan.mode(), plan.styleProfile(), plan.anchor(), plan.globalConstraints(), layout,
                plan.components(), plan.genome(), plan.styleAttributes(), plan.proportionHints(),
                plan.alignmentAndSymmetry(), plan.targetSlotId(), plan.allowedArea(), plan.patch(),
                plan.planProgram(), plan.planSkeleton(), plan.planStatus(), plan.error(),
                plan.capabilityGap(), plan.playerFidelityNoticeZh(), plan.distinguishingFeatures(),
                plan.enrichmentGuard(), plan.planMeta(), plan.designSources());
    }

    /**
     * Preserve components; attach a capability gap for replan. Also stamps compile diagnostics
     * so ComponentPlanCompiler short-circuits the same way as other repairs.
     */
    static LlmPlan withCapabilityGap(LlmPlan plan, CapabilityGap gap) {
        if (plan == null) return null;
        if (gap != null) {
            AssemblyCompileDiagnostics.set(gap);
        }
        return new LlmPlan(
                plan.mode(), plan.styleProfile(), plan.anchor(), plan.globalConstraints(), plan.layout(),
                plan.components(), plan.genome(), plan.styleAttributes(), plan.proportionHints(),
                plan.alignmentAndSymmetry(), plan.targetSlotId(), plan.allowedArea(), plan.patch(),
                plan.planProgram(), plan.planSkeleton(), "capability_gap", plan.error(),
                gap, plan.playerFidelityNoticeZh(), plan.distinguishingFeatures(),
                plan.enrichmentGuard(), plan.planMeta(), plan.designSources());
    }
}
