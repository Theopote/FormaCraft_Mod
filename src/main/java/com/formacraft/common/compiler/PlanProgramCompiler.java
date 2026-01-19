package com.formacraft.common.compiler;

import com.formacraft.common.llm.compiler.CompiledSkeleton;
import com.formacraft.common.llm.compiler.PlanCompileContext;
import com.formacraft.common.llm.compiler.PlanToSkeletonIntegrationHelper;
import com.formacraft.common.llm.dto.PlanProgram;
import com.formacraft.common.llm.dto.PlanSkeleton;
import com.formacraft.common.llm.converter.PlanProgramToPlanSkeletonConverter;
import com.formacraft.common.llm.parser.PlanProgramParser;
import com.formacraft.common.llm.parser.PlanSkeletonParser;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.FormacraftMod;
import com.formacraft.server.skeleton.gen.ExecutableSkeletonPlan;
import com.formacraft.server.skeleton.gen.SkeletonBuildService;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * PlanProgramCompiler（平面程序编译器）
 * <p>
 * 核心职责：将 PlanProgram 或 PlanSkeleton 编译为 BlockPatch 列表
 * <p>
 * 这是新的编译管线入口点，与 ComponentPlanCompiler 并行存在：
 * - ComponentPlanCompiler：传统 components[] 模式
 * - PlanProgramCompiler：新的 PlanProgram → Skeleton 模式
 * <p>
 * 完整链路：
 * PlanProgram/PlanSkeleton → CompiledSkeleton → ExecutableSkeletonPlan → Generator → BlockPatch
 */
public final class PlanProgramCompiler {

    private PlanProgramCompiler() {}

    /**
     * 从 PlanProgram 编译为 BlockPatch 列表
     * <p>
     * 流程：
     * 1. PlanProgram → PlanSkeleton
     * 2. PlanSkeleton → CompiledSkeleton（包含 ExecutableSkeletonPlan + ExtrudedSolid）
     * 3. ExecutableSkeletonPlan → Generator → BlockPatch（使用现有 Generator 系统）
     * 
     * @param planProgram PlanProgram
     * @param globalAnchor 全局 anchor（世界坐标）
     * @param world 服务器世界（可选）
     * @return BlockPatch 列表
     */
    public static List<BlockPatch> compile(
            PlanProgram planProgram,
            BlockPos globalAnchor,
            ServerWorld world
    ) {
        if (planProgram == null) {
            FormacraftMod.LOGGER.warn("PlanProgramCompiler: planProgram is null");
            return List.of();
        }

        try {
            // Step 1: PlanProgram → PlanSkeleton
            PlanSkeleton planSkeleton = PlanProgramToPlanSkeletonConverter.convert(planProgram);

            // Step 2: PlanSkeleton → CompiledSkeleton（使用编译器）
            return compileFromPlanSkeleton(planSkeleton, globalAnchor, world);

        } catch (Exception e) {
            FormacraftMod.LOGGER.error("PlanProgramCompiler: compilation failed", e);
            return List.of();
        }
    }

    /**
     * 从 PlanSkeleton 编译为 BlockPatch 列表
     * <p>
     * 如果 PlanSkeleton 已经存在（例如从 JSON 解析），可以直接使用这个方法。
     * 
     * @param planSkeleton PlanSkeleton
     * @param globalAnchor 全局 anchor（世界坐标）
     * @param world 服务器世界（可选）
     * @return BlockPatch 列表
     */
    public static List<BlockPatch> compileFromPlanSkeleton(
            PlanSkeleton planSkeleton,
            BlockPos globalAnchor,
            ServerWorld world
    ) {
        if (planSkeleton == null) {
            FormacraftMod.LOGGER.warn("PlanProgramCompiler: planSkeleton is null");
            return List.of();
        }

        try {
            // 创建编译上下文
            PlanCompileContext context = world != null && globalAnchor != null
                    ? PlanCompileContext.createWithTerrain(world, globalAnchor)
                    : PlanCompileContext.createDefault();

            // 编译 PlanSkeleton → CompiledSkeleton
            CompiledSkeleton compiled = PlanToSkeletonIntegrationHelper.compileFromPlanSkeleton(planSkeleton, context);

            FormacraftMod.LOGGER.info(
                    "PlanProgramCompiler: compiled {} skeletons ({} extruded solids)",
                    compiled.getSkeletons().size(),
                    PlanToSkeletonIntegrationHelper.extractExtrudedSolids(compiled).size()
            );

            // Step 3: ExecutableSkeletonPlan → Generator → BlockPatch
            // 使用 SkeletonBuildService 将每个 ExecutableSkeletonPlan 转换为 BlockPatch
            if (compiled.isEmpty() || world == null || globalAnchor == null) {
                FormacraftMod.LOGGER.debug("PlanProgramCompiler: skipping generator step (empty skeletons or missing world/anchor)");
                return List.of();
            }

            return generateBlockPatchesFromSkeletons(compiled.getSkeletons(), globalAnchor, world);

        } catch (Exception e) {
            FormacraftMod.LOGGER.error("PlanProgramCompiler: compilation from PlanSkeleton failed", e);
            return List.of();
        }
    }

    /**
     * 从 JSON 字符串编译（便捷方法）
     * <p>
     * 自动检测是 PlanProgram 还是 PlanSkeleton
     */
    public static List<BlockPatch> compileFromJson(
            String json,
            BlockPos globalAnchor,
            ServerWorld world
    ) {
        if (json == null || json.isBlank()) {
            return List.of();
        }

        try {
            // 尝试解析为 PlanSkeleton（优先）
            try {
                PlanSkeleton planSkeleton = PlanSkeletonParser.parseAndValidate(json);
                return compileFromPlanSkeleton(planSkeleton, globalAnchor, world);
            } catch (Exception e1) {
                // 尝试解析为 PlanProgram
                try {
                    PlanProgram planProgram = PlanProgramParser.parseAndValidate(json);
                    return compile(planProgram, globalAnchor, world);
                } catch (Exception e2) {
                    FormacraftMod.LOGGER.warn("PlanProgramCompiler: failed to parse as PlanSkeleton or PlanProgram", e2);
                    return List.of();
                }
            }
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("PlanProgramCompiler: JSON compilation failed", e);
            return List.of();
        }
    }

    /**
     * 从 ExecutableSkeletonPlan 列表生成 BlockPatch 列表
     * <p>
     * 使用 SkeletonBuildService 将每个 skeleton 转换为 BlockPatch
     * <p>
     * 处理逻辑：
     * 1. 为每个 ExecutableSkeletonPlan 调用 Generator
     * 2. 所有 BlockPatch 使用相同的 origin（globalAnchor）
     * 3. 合并所有生成的 BlockPatch
     *
     * @param skeletons ExecutableSkeletonPlan 列表
     * @param origin 世界原点（BlockPatch 的相对坐标基准）
     * @param world 服务器世界
     * @return BlockPatch 列表（相对 origin 的偏移）
     */
    private static List<BlockPatch> generateBlockPatchesFromSkeletons(
            List<ExecutableSkeletonPlan> skeletons,
            BlockPos origin,
            ServerWorld world
    ) {
        if (skeletons == null || skeletons.isEmpty() || world == null || origin == null) {
            return List.of();
        }

        List<BlockPatch> allPatches = new ArrayList<>();
        SkeletonBuildService buildService = new SkeletonBuildService();

        for (ExecutableSkeletonPlan skeleton : skeletons) {
            if (skeleton == null) {
                continue;
            }

            try {
                // 使用 SkeletonBuildService 生成 BlockPatch
                // 注意：SkeletonBuildService 会优先使用新的语义系统，如果没有则回退到旧生成器
                List<BlockPatch> patches = buildService.build(world, origin, skeleton, "DEFAULT");

                if (patches != null && !patches.isEmpty()) {
                    allPatches.addAll(patches);
                    FormacraftMod.LOGGER.debug(
                            "PlanProgramCompiler: generated {} patches for skeleton type {}",
                            patches.size(),
                            skeleton.type
                    );
                } else {
                    FormacraftMod.LOGGER.debug(
                            "PlanProgramCompiler: no patches generated for skeleton type {}",
                            skeleton.type
                    );
                }
            } catch (Exception e) {
                FormacraftMod.LOGGER.warn(
                        "PlanProgramCompiler: failed to generate patches for skeleton type {}: {}",
                        skeleton.type,
                        e.getMessage()
                );
                // 继续处理其他 skeleton，不中断整个流程
            }
        }

        FormacraftMod.LOGGER.info(
                "PlanProgramCompiler: generated {} total patches from {} skeletons",
                allPatches.size(),
                skeletons.size()
        );

        return allPatches;
    }
}
