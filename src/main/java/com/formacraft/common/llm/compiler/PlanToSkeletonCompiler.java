package com.formacraft.common.llm.compiler;

import com.formacraft.common.llm.dto.PlanSkeleton;

/**
 * PlanToSkeletonCompiler（平面骨架编译器）
 * <p>
 * 🎯 架构校准后（2026-01-14）：
 * 当前编译器执行的是"简化流程"，直接进行 PlanSkeleton → StructuralSkeleton → ExecutableSkeletonPlan 的转换。
 * <p>
 * ⚠️ 正确的架构应该是：
 * ```
 * PlanSkeleton (Domain)
 *   ↓
 * Building Mass Assembly (体量组合)
 *   ↓
 * StructuralSkeleton (从体量组合派生)
 *   ↓
 * ExecutableSkeletonPlan
 * ```
 * <p>
 * 当前流程（简化版本）：
 * ```
 * PlanSkeleton → StructuralSkeleton → ExecutableSkeletonPlan
 * ```
 * <p>
 * 未来方向：
 * - 在 PlanSkeleton 和 StructuralSkeleton 之间插入 Building Mass Assembly 层
 * - 让 StructuralSkeleton 真正从体量组合派生
 * <p>
 * 核心职责（当前简化版本）：
 * 将 PlanSkeleton（2D Domain）编译成一组可被 Socket / Skeleton 系统消费的 3D Skeleton。
 * <p>
 * 注意：
 * - 生成的 StructuralSkeleton 应该被视为"候选结构模板"
 * - 不是"必然实例化"的结构
 * <p>
 * 设计目标：
 * - 不自动生成构件
 * - 不负责风格
 * - 不负责材质
 * - 只做一件事：PlanSkeleton → CompiledSkeleton（当前简化版本）
 * <p>
 * 在整个流水线中的位置（校准后）：
 * <pre>
 * LLM
 *   ↓
 * PlanProgram
 *   ↓
 * PlanSkeleton (Domain)
 *   ↓
 * PlanToSkeletonCompiler   ← ★ 当前简化版本
 *   ↓
 * StructuralSkeleton (候选结构)
 *   ↓
 * CompiledSkeleton (ExecutableSkeletonPlan + SkeletonGraph)
 *   ↓
 * SocketProvider
 *   ↓
 * Component (方块)
 * </pre>
 * <p>
 * 这是 AI → 几何 → 构件 的关键编译节点（当前简化版本）。
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
