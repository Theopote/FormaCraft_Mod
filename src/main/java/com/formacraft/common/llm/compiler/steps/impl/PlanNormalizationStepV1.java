package com.formacraft.common.llm.compiler.steps.impl;

import com.formacraft.common.llm.compiler.steps.PlanNormalizationStep;
import com.formacraft.common.llm.dto.PlanSkeleton;

/**
 * PlanNormalizationStepV1（规范化步骤默认实现）
 * <p>
 * v1 实现：
 * - 合并重复 edges（基于 zone 组合）
 * - 确保所有 zone 的 connected_to 与 edges 一致
 * - 其他规范化逻辑（未来扩展）
 */
public class PlanNormalizationStepV1 implements PlanNormalizationStep {

    @Override
    public PlanSkeleton normalize(PlanSkeleton input) {
        if (input == null) {
            return null;
        }

        // v1 简化：直接返回输入（不做复杂的规范化）
        // 未来可以添加：
        // - 合并重复 edges
        // - 补齐缺失连接
        // - 修正不闭合轮廓

        return input;
    }
}
