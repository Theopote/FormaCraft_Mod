package com.formacraft.server.generation;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.server.generation.component.adaptor.UnifiedGeneratorRouter;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.skeleton.ExecutableSkeletonPlan;
import com.formacraft.common.skeleton.SkeletonExecutors;
import com.formacraft.server.generation.structure.StructureGenerator;
import com.formacraft.server.generation.structure.StructureGeneratorFactory;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * 生成系统统一入口（Phase 3）。
 * <p>
 * 三种粒度，三个子系统，一个门面：
 * <ul>
 *   <li>整栋建筑：{@link #routeStructure(BuildingSpec)} → {@code GeneratorRouter}</li>
 *   <li>LLM 构件：{@link #generateComponent(SemanticComponent, ServerWorld)} → {@link UnifiedGeneratorRouter}</li>
 *   <li>骨架：{@link #buildSkeleton(ServerWorld, BlockPos, ExecutableSkeletonPlan, String)} → {@link SkeletonExecutors}</li>
 * </ul>
 */
public final class GenerationHub {

    private GenerationHub() {}

    /** BuildingSpec 整栋路由（BuildRequestProcessor / CityBuilder / commands）。 */
    public static StructureGenerator routeStructure(BuildingSpec spec) {
        return StructureGeneratorFactory.getGenerator(spec);
    }

    /** LlmPlan 构件路由（ComponentPlanCompiler）。 */
    public static List<BlockPatch> generateComponent(SemanticComponent semantic, ServerWorld world) {
        return UnifiedGeneratorRouter.generate(semantic, world);
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
