package com.formacraft.common.llm.compiler.steps.impl;

import com.formacraft.common.llm.compiler.PlanCompileContext;
import com.formacraft.common.llm.compiler.steps.SkeletonGenerationStep;
import com.formacraft.common.llm.dto.StructuralSkeleton;
import com.formacraft.common.llm.converter.StructuralSkeletonToExecutablePlanConverter;
import com.formacraft.common.skeleton.ExecutableSkeletonPlan;

import java.util.List;

/**
 * SkeletonGenerationStepV1（骨架生成步骤默认实现）
 * <p>
 * 使用 StructuralSkeletonToExecutablePlanConverter 进行转换
 */
public class SkeletonGenerationStepV1 implements SkeletonGenerationStep {

    @Override
    public List<ExecutableSkeletonPlan> generateSkeletons(
            StructuralSkeleton structural,
            PlanCompileContext context
    ) {
        if (structural == null) {
            return List.of();
        }

        // 使用已有的转换器
        List<ExecutableSkeletonPlan> plans = StructuralSkeletonToExecutablePlanConverter.convert(structural);

        // 应用上下文中的默认值（如果 skeleton 没有明确指定）
        if (context != null) {
            for (ExecutableSkeletonPlan plan : plans) {
                // 如果 plan 没有明确的高度，使用上下文的默认值
                if (plan.height <= 0) {
                    plan.height = (int) context.defaultWallHeight;
                    plan.put("height", plan.height);
                }
            }
        }

        return plans;
    }
}
