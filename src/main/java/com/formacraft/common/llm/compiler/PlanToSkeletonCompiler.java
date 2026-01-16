package com.formacraft.common.llm.compiler;

import com.formacraft.common.llm.dto.PlanSkeleton;

/**
 * PlanToSkeletonCompiler（平面骨架编译器）
 * <p>
 * 核心职责：把 PlanSkeleton（2D 语义骨架）编译成一组可被 Socket / Skeleton 系统消费的 3D Skeleton
 * <p>
 * 设计目标：
 * - 不自动生成构件
 * - 不负责风格
 * - 不负责材质
 * - 只做一件事：PlanSkeleton → CompiledSkeleton
 * <p>
 * 在整个流水线中的位置：
 * <pre>
 * LLM
 *   ↓
 * PlanProgram
 *   ↓
 * PlanSkeleton
 *   ↓
 * PlanToSkeletonCompiler   ← ★ 本次设计
 *   ↓
 * CompiledSkeleton (ExecutableSkeletonPlan + SkeletonGraph)
 *   ↓
 * SocketProvider
 *   ↓
 * AssemblyPlanner / AutoAssembler
 * </pre>
 * <p>
 * 这是 AI → 几何 → 构件 的关键编译节点。
 */
public interface PlanToSkeletonCompiler {

    /**
     * Compile a 2D PlanSkeleton into 3D architectural Skeletons.
     *
     * @param planSkeleton semantic 2D plan representation
     * @param context build-time context (terrain, config, defaults)
     * @return compiled skeleton graph
     */
    CompiledSkeleton compile(
            PlanSkeleton planSkeleton,
            PlanCompileContext context
    );
}
