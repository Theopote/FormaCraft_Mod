package com.formacraft.common.llm.compiler;

import com.formacraft.common.geometry.extrusion.ExtrudedSolid;
import com.formacraft.common.llm.dto.PlanSkeleton;
import com.formacraft.common.skeleton.ExecutableSkeletonPlan;

import java.util.ArrayList;
import java.util.List;

/**
 * PlanToSkeletonIntegrationHelper（整合辅助类）
 * <p>
 * 提供便捷方法，整合 PlanSkeleton → CompiledSkeleton 的完整流程
 * <p>
 * 这是"一步到位"的入口点，适合：
 * - 快速原型
 * - 测试
 * - 简单用例
 */
public final class PlanToSkeletonIntegrationHelper {

    private PlanToSkeletonIntegrationHelper() {}

    /**
     * 从 PlanSkeleton 生成 CompiledSkeleton（完整流程）
     * <p>
     * 整合了：
     * - PlanSkeleton → StructuralSkeleton
     * - StructuralSkeleton → ExecutableSkeletonPlan
     * - WallExtrusion（自动执行）
     * - SkeletonGraph 构建
     * 
     * @param planSkeleton PlanSkeleton（2D 语义）
     * @param context 编译上下文（可选，使用默认值如果为 null）
     * @return CompiledSkeleton
     */
    public static CompiledSkeleton compileFromPlanSkeleton(
            PlanSkeleton planSkeleton,
            PlanCompileContext context
    ) {
        if (planSkeleton == null) {
            return new CompiledSkeleton(List.of(), new SkeletonGraph());
        }

        if (context == null) {
            context = PlanCompileContext.createDefault();
        }

        PlanToSkeletonCompiler compiler = new PlanToSkeletonCompilerV1();
        return compiler.compile(planSkeleton, context);
    }

    /**
     * 从 PlanSkeleton 生成 CompiledSkeleton（使用默认上下文）
     */
    public static CompiledSkeleton compileFromPlanSkeleton(PlanSkeleton planSkeleton) {
        return compileFromPlanSkeleton(planSkeleton, null);
    }

    /**
     * 从 CompiledSkeleton 提取所有 ExtrudedSolid
     * <p>
     * 用于可视化、调试、几何查询等
     * 
     * @param compiled CompiledSkeleton
     * @return ExtrudedSolid 列表
     */
    public static List<ExtrudedSolid> extractExtrudedSolids(CompiledSkeleton compiled) {
        List<ExtrudedSolid> solids = new ArrayList<>();

        if (compiled == null || compiled.getSkeletons().isEmpty()) {
            return solids;
        }

        for (ExecutableSkeletonPlan plan : compiled.getSkeletons()) {
            // 尝试获取单个 solid
            ExtrudedSolid solid = plan.get("extruded_solid", null);
            if (solid != null) {
                solids.add(solid);
            }

            // 尝试获取多个 solids（折线墙）
            List<ExtrudedSolid> multipleSolids = plan.get("extruded_solids", null);
            if (multipleSolids != null) {
                solids.addAll(multipleSolids);
            }
        }

        return solids;
    }

    /**
     * 获取编译统计信息（用于调试）
     */
    public static CompilationStats getStats(CompiledSkeleton compiled) {
        if (compiled == null) {
            return new CompilationStats(0, 0, 0);
        }

        int skeletonCount = compiled.getSkeletons().size();
        int extrudedSolidCount = extractExtrudedSolids(compiled).size();
        int graphNodeCount = compiled.getGraph().getAllSkeletons().size();

        return new CompilationStats(skeletonCount, extrudedSolidCount, graphNodeCount);
    }

    /**
     * 编译统计信息
     */
    public record CompilationStats(
            int skeletonCount,
            int extrudedSolidCount,
            int graphNodeCount
    ) {}
}
