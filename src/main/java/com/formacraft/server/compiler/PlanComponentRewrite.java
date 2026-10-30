package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.List;

/** Shared LlmPlan reconstruction when only components[] change. */
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
}
