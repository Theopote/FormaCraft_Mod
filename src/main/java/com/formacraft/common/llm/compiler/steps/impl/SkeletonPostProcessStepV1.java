package com.formacraft.common.llm.compiler.steps.impl;

import com.formacraft.common.llm.compiler.PlanCompileContext;
import com.formacraft.common.llm.compiler.steps.SkeletonPostProcessStep;
import com.formacraft.server.skeleton.gen.ExecutableSkeletonPlan;

import java.util.List;

/**
 * SkeletonPostProcessStepV1（后处理步骤默认实现）
 * <p>
 * v1 实现：
 * - 添加 heightLevel tag（GROUND）
 * - 其他后处理（未来扩展）
 */
public class SkeletonPostProcessStepV1 implements SkeletonPostProcessStep {

    @Override
    public List<ExecutableSkeletonPlan> postProcess(
            List<ExecutableSkeletonPlan> skeletons,
            PlanCompileContext context
    ) {
        if (skeletons == null || skeletons.isEmpty()) {
            return skeletons != null ? skeletons : List.of();
        }

        // v1 简化：添加默认 tag
        for (ExecutableSkeletonPlan skeleton : skeletons) {
            // 添加 heightLevel tag（默认 GROUND）
            if (!skeleton.params.containsKey("heightLevel")) {
                skeleton.put("heightLevel", "GROUND");
            }
        }

        // 未来可以添加：
        // - 合并共线墙段
        // - 标注 corner / edge

        return skeletons;
    }
}
