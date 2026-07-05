package com.formacraft.common.orchestrator;

/**
 * Python 编排器 {@code /build} 响应的类型安全联合。
 * <p>
 * 替代将 {@link com.formacraft.common.llm.dto.LlmPlan} 塞进假
 * {@link com.formacraft.common.model.build.BuildingSpec#extra} 的做法。
 */
public sealed interface AiPlanResult
        permits AiPlanResult.LlmPlan, AiPlanResult.BuildingSpec, AiPlanResult.CompositeSpec,
                AiPlanResult.CitySpec, AiPlanResult.Clarification {

    record LlmPlan(com.formacraft.common.llm.dto.LlmPlan plan) implements AiPlanResult {}

    record BuildingSpec(com.formacraft.common.model.build.BuildingSpec spec) implements AiPlanResult {}

    record CompositeSpec(com.formacraft.common.model.composite.CompositeSpec spec) implements AiPlanResult {}

    record CitySpec(com.formacraft.common.model.city.CitySpec spec) implements AiPlanResult {}

    record Clarification(ClarificationResponse response) implements AiPlanResult {}
}
