package com.formacraft.server.generation;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.server.generation.component.adaptor.UnifiedGeneratorRouter;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.skeleton.ExecutableSkeletonPlan;
import com.formacraft.server.skeleton.SkeletonExecutors;
import com.formacraft.server.generation.structure.StructureGenerator;
import com.formacraft.server.generation.structure.StructureGeneratorFactory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * 生成系统统一执行门面。
 * <p>
 * 三种粒度，一个门面（规划协议仍以 LlmPlan 为默认）：
 * <ul>
 *   <li>整栋<strong>执行器</strong>：{@link #routeStructure(BuildingSpec)} → {@code GeneratorRouter}
 *       （显式 buildingspec / CityBuilder / landmark MODULE adaptor；非独立规划主干）</li>
 *   <li>LLM 构件：{@link #generateComponent(SemanticComponent, ServerWorld)} → {@link UnifiedGeneratorRouter}</li>
 *   <li>骨架：{@link #buildSkeleton(ServerWorld, BlockPos, ExecutableSkeletonPlan, String)} → {@link SkeletonExecutors}</li>
 * </ul>
 * 禁止把 StructureGenerator 合并为上帝类；能力与参数注入保持显式。
 */
public final class GenerationHub {

    private GenerationHub() {}

    /**
     * BuildingSpec → StructureGenerator 执行器路由。
     * 调用方：compat 预览、CityBuilder、landmark adaptor、蓝图加载。
     */
    public static StructureGenerator routeStructure(BuildingSpec spec) {
        return StructureGeneratorFactory.getGenerator(spec);
    }

    /** LlmPlan 构件路由（ComponentPlanCompiler）。 */
    public static List<BlockPatch> generateComponent(SemanticComponent semantic, ServerWorld world) {
        return UnifiedGeneratorRouter.generate(semantic, world);
    }

    /** Structured route — prefer when failure must abort plan compile. */
    public static com.formacraft.server.generation.component.adaptor.RoutedGenerationResult generateComponentResult(
            SemanticComponent semantic, ServerWorld world) {
        return UnifiedGeneratorRouter.generateResult(semantic, world);
    }

    /** 骨架计划 → BlockPatch（PlanProgramCompiler / 内嵌 skeleton 构件）。 */
    public static List<BlockPatch> buildSkeleton(
            ServerWorld world,
            BlockPos origin,
            ExecutableSkeletonPlan plan,
            String paletteId
    ) {
        return SkeletonExecutors.get().build(world, origin, plan, paletteId);
    }
}
