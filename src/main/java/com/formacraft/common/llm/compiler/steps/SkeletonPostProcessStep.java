package com.formacraft.common.llm.compiler.steps;

import com.formacraft.common.llm.compiler.PlanCompileContext;
import com.formacraft.common.skeleton.ExecutableSkeletonPlan;

import java.util.List;

/**
 * SkeletonPostProcessStep（后处理步骤）
 * <p>
 * v1 可做的事：
 * - 合并共线墙段
 * - 标注 corner / edge
 * - 添加 heightLevel tag（GROUND / MID / TOP）
 */
public interface SkeletonPostProcessStep {
    /**
     * 后处理 ExecutableSkeletonPlan 列表
     * 
     * @param skeletons 骨架计划列表
     * @param context 编译上下文
     * @return 处理后的骨架计划列表
     */
    List<ExecutableSkeletonPlan> postProcess(
            List<ExecutableSkeletonPlan> skeletons,
            PlanCompileContext context
    );
}
