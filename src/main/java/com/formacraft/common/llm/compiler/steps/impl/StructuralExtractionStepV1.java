package com.formacraft.common.llm.compiler.steps.impl;

import com.formacraft.common.llm.compiler.PlanCompileContext;
import com.formacraft.common.llm.compiler.steps.StructuralExtractionStep;
import com.formacraft.common.llm.dto.PlanSkeleton;
import com.formacraft.common.llm.dto.StructuralSkeleton;
import com.formacraft.common.llm.converter.PlanSkeletonToStructuralSkeletonConverter;

/**
 * StructuralExtractionStepV1（结构提取步骤默认实现）
 * <p>
 * 使用 PlanSkeletonToStructuralSkeletonConverter 进行转换
 */
public class StructuralExtractionStepV1 implements StructuralExtractionStep {

    @Override
    public StructuralSkeleton extract(PlanSkeleton planSkeleton, PlanCompileContext context) {
        if (planSkeleton == null) {
            return null;
        }

        // 使用已有的转换器
        return PlanSkeletonToStructuralSkeletonConverter.convert(planSkeleton);
    }
}
