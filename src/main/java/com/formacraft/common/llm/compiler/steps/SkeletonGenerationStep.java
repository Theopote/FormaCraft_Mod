package com.formacraft.common.llm.compiler.steps;

import com.formacraft.common.llm.compiler.PlanCompileContext;
import com.formacraft.common.llm.dto.StructuralSkeleton;
import com.formacraft.common.skeleton.ExecutableSkeletonPlan;

import java.util.List;

/**
 * SkeletonGenerationStep（骨架生成步骤）
 * <p>
 * 作用：将 StructuralSkeleton 转换为 ExecutableSkeletonPlan 列表
 * <p>
 * 与现有系统强耦合：使用 ExecutableSkeletonPlan 和 SkeletonType
 */
public interface SkeletonGenerationStep {
    /**
     * 从 StructuralSkeleton 生成 ExecutableSkeletonPlan 列表
     * 
     * @param structural 3D 结构骨架
     * @param context 编译上下文
     * @return ExecutableSkeletonPlan 列表
     */
    List<ExecutableSkeletonPlan> generateSkeletons(
            StructuralSkeleton structural,
            PlanCompileContext context
    );
}
