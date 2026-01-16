package com.formacraft.common.llm.compiler.steps;

import com.formacraft.common.llm.dto.PlanSkeleton;

/**
 * PlanNormalizationStep（规范化步骤）
 * <p>
 * 作用：
 * - 合并重复 edges
 * - 补齐缺失连接
 * - 修正不闭合轮廓
 * <p>
 * 👉 非常适合处理 LLM 的"半正确输出"
 */
public interface PlanNormalizationStep {
    /**
     * 规范化 PlanSkeleton
     * 
     * @param input 输入 PlanSkeleton
     * @return 规范化后的 PlanSkeleton
     */
    PlanSkeleton normalize(PlanSkeleton input);
}
