package com.formacraft.common.debug;

import com.formacraft.common.llm.compiler.CompiledSkeleton;
import com.formacraft.common.llm.dto.PlanSkeleton;
import com.formacraft.common.llm.dto.StructuralSkeleton;
import com.formacraft.server.skeleton.gen.ExecutableSkeletonPlan;

import java.util.List;

/**
 * DebugContext（调试上下文）
 * <p>
 * 存储渲染所需的所有上下文信息
 * <p>
 * 设计原则：
 * - 包含所有层的中间数据
 * - 支持部分数据为 null（渐进式调试）
 * - 不包含渲染状态（由 Renderer 管理）
 */
public class DebugContext {
    /** PlanSkeleton（2D 语义） */
    public final PlanSkeleton planSkeleton;

    /** StructuralSkeleton（3D 结构） */
    public final StructuralSkeleton structuralSkeleton;

    /** CompiledSkeleton（编译结果） */
    public final CompiledSkeleton compiledSkeleton;

    /** ExecutableSkeletonPlan 列表（可执行计划） */
    public final List<ExecutableSkeletonPlan> executablePlans;

    /** 当前查看的 Y 高度（用于 2D overlay） */
    public final double viewY;

    /** 渲染比例（缩放） */
    public final double scale;

    public DebugContext(
            PlanSkeleton planSkeleton,
            StructuralSkeleton structuralSkeleton,
            CompiledSkeleton compiledSkeleton,
            List<ExecutableSkeletonPlan> executablePlans,
            double viewY,
            double scale
    ) {
        this.planSkeleton = planSkeleton;
        this.structuralSkeleton = structuralSkeleton;
        this.compiledSkeleton = compiledSkeleton;
        this.executablePlans = executablePlans;
        this.viewY = viewY;
        this.scale = scale;
    }

    /**
     * 创建默认上下文
     */
    public static DebugContext createDefault(PlanSkeleton planSkeleton) {
        return new DebugContext(
                planSkeleton,
                null,
                null,
                null,
                0.0,
                1.0
        );
    }

    /**
     * 创建完整上下文
     */
    public static DebugContext createFull(
            PlanSkeleton planSkeleton,
            StructuralSkeleton structuralSkeleton,
            CompiledSkeleton compiledSkeleton
    ) {
        List<ExecutableSkeletonPlan> plans = compiledSkeleton != null
                ? compiledSkeleton.getSkeletons()
                : null;

        return new DebugContext(
                planSkeleton,
                structuralSkeleton,
                compiledSkeleton,
                plans,
                0.0,
                1.0
        );
    }
}
