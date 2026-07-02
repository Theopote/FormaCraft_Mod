package com.formacraft.common.llm.compiler;

import com.formacraft.common.skeleton.ExecutableSkeletonPlan;

import java.util.List;

/**
 * CompiledSkeleton（编译后的骨架）
 * <p>
 * 这是 Socket 系统的直接输入。
 * <p>
 * 包含：
 * - skeletons: ExecutableSkeletonPlan 列表（与现有系统兼容）
 * - graph: SkeletonGraph（用于调试、AI 修正、UI 高亮）
 * <p>
 * SkeletonGraph 的用途：
 * - 哪面墙属于哪个 zone
 * - 哪些 skeleton 是相邻的
 * - 哪些是 courtyard / exterior / shared
 * <p>
 * 👉 这是调试、AI 修正、UI 高亮的基础
 */
public class CompiledSkeleton {
    private final List<ExecutableSkeletonPlan> skeletons;
    private final SkeletonGraph graph;

    public CompiledSkeleton(
            List<ExecutableSkeletonPlan> skeletons,
            SkeletonGraph graph
    ) {
        this.skeletons = skeletons != null ? skeletons : List.of();
        this.graph = graph != null ? graph : new SkeletonGraph();
    }

    public List<ExecutableSkeletonPlan> getSkeletons() {
        return skeletons;
    }

    public SkeletonGraph getGraph() {
        return graph;
    }

    public boolean isEmpty() {
        return skeletons.isEmpty();
    }

    public int size() {
        return skeletons.size();
    }
}
