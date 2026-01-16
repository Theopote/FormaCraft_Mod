package com.formacraft.common.llm.compiler.steps;

import com.formacraft.common.llm.compiler.PlanCompileContext;
import com.formacraft.common.llm.dto.PlanSkeleton;
import com.formacraft.common.llm.dto.StructuralSkeleton;

/**
 * StructuralExtractionStep（结构提取步骤）
 * <p>
 * 作用：将 PlanSkeleton 的"2D 几何语义"转换为 StructuralSkeleton 的"3D 结构骨架"
 * <p>
 * StructuralSkeleton 是 PlanSkeleton → 3D 之前的"最后一层纯语义结构"
 */
public interface StructuralExtractionStep {
    /**
     * 从 PlanSkeleton 提取 StructuralSkeleton
     * 
     * @param planSkeleton 2D 几何语义
     * @param context 编译上下文
     * @return 3D 结构骨架
     */
    StructuralSkeleton extract(PlanSkeleton planSkeleton, PlanCompileContext context);
}
