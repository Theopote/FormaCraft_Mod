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
import com.formacraft.common.skeleton.ExecutableSkeletonPlan;
import com.formacraft.common.skeleton.SkeletonExecutors;
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
    private static final int MAX_PLAN_PATCHES = 200_000;

    public static final class SkeletonCompilationFailure extends IllegalArgumentException {
        private SkeletonCompilationFailure(String message, Throwable cause) { super(message, cause); }
    }

    static List<BlockPatch> mergeSkeletonPatches(List<ExecutableSkeletonPlan> skeletons,
            java.util.function.Function<ExecutableSkeletonPlan, List<BlockPatch>> generate) {
        return mergeSkeletonPatches(skeletons, generate, MAX_PLAN_PATCHES);
    }

    static List<BlockPatch> mergeSkeletonPatches(List<ExecutableSkeletonPlan> skeletons,
            java.util.function.Function<ExecutableSkeletonPlan, List<BlockPatch>> generate, int maxPatches) {
        if (maxPatches < 0) throw new IllegalArgumentException("Negative plan patch budget");
        List<BlockPatch> merged = new ArrayList<>();
        for (int i = 0; i < skeletons.size(); i++) {
            var skeleton = skeletons.get(i);
            try {
                if (skeleton == null) throw new IllegalArgumentException("null skeleton");
                var patches = generate.apply(skeleton);
                if (patches == null || patches.isEmpty()) throw new IllegalArgumentException("empty skeleton output");
                if ((long) merged.size() + patches.size() > maxPatches)
                    throw new IllegalArgumentException("PlanProgram exceeds cumulative patch budget " + maxPatches);
                for (var patch : patches) {
                    if (com.formacraft.common.patch.BlockPatchTargetResolver.resolve(patch) == null)
                        throw new IllegalArgumentException("invalid skeleton patch");
                }
                merged.addAll(patches);
            } catch (RuntimeException failure) {
                throw new SkeletonCompilationFailure("PlanProgram skeleton " + (i + 1) + " failed; no partial building is returned", failure);
            }
        }
        return merged;
    }

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
        return compile(planProgram, globalAnchor, world, null);
    }

    /**
     * 从 PlanProgram 编译为 BlockPatch 列表（带风格支持）
     * 
     * @param planProgram PlanProgram
     * @param globalAnchor 全局 anchor（世界坐标）
     * @param world 服务器世界（可选）
     * @param styleProfileId 风格配置文件 ID（可选）
     * @return BlockPatch 列表
     */
    public static List<BlockPatch> compile(
            PlanProgram planProgram,
            BlockPos globalAnchor,
            ServerWorld world,
            String styleProfileId
    ) {
        if (planProgram == null) {
            FormacraftMod.LOGGER.warn("PlanProgramCompiler: planProgram is null");
            return List.of();
        }

        try {
            // Step 1: PlanProgram → PlanSkeleton
            PlanSkeleton planSkeleton = PlanProgramToPlanSkeletonConverter.convert(planProgram);

            // Step 2: PlanSkeleton → CompiledSkeleton（使用编译器）
            return compileFromPlanSkeleton(planSkeleton, globalAnchor, world, styleProfileId);

        } catch (Exception e) {
            throw new SkeletonCompilationFailure("PlanProgram compilation failed", e);
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
        return compileFromPlanSkeleton(planSkeleton, globalAnchor, world, null);
    }

    /**
     * 从 PlanSkeleton 编译为 BlockPatch 列表（带风格支持）
     * 
     * @param planSkeleton PlanSkeleton
     * @param globalAnchor 全局 anchor（世界坐标）
     * @param world 服务器世界（可选）
     * @param styleProfileId 风格配置文件 ID（可选）
     * @return BlockPatch 列表
     */
    public static List<BlockPatch> compileFromPlanSkeleton(
            PlanSkeleton planSkeleton,
            BlockPos globalAnchor,
            ServerWorld world,
            String styleProfileId
    ) {
        return compileFromPlanSkeleton(planSkeleton, globalAnchor, world, styleProfileId, null);
    }

    /**
     * 从 PlanSkeleton 编译为 BlockPatch 列表（带风格 + C1 outline 支持）。
     * <p>
     * 当提供 {@code outline} 时，编译上下文会据其生成真实多边形楼板（替代写死矩形）。
     *
     * @param outline 用户/系统轮廓（可选，可为 null）
     */
    public static List<BlockPatch> compileFromPlanSkeleton(
            PlanSkeleton planSkeleton,
            BlockPos globalAnchor,
            ServerWorld world,
            String styleProfileId,
            com.formacraft.common.buildcontext.OutlineShape outline
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
            // C1：把 outline 注入上下文，让 FloorPlate 从真实轮廓生成。
            if (outline != null) {
                context = context.withOutline(outline);
            }

            // 编译 PlanSkeleton → CompiledSkeleton
            CompiledSkeleton compiled = PlanToSkeletonIntegrationHelper.compileFromPlanSkeleton(planSkeleton, context);

            FormacraftMod.LOGGER.info(
                    "PlanProgramCompiler: compiled {} skeletons ({} extruded solids)",
                    compiled.getSkeletons().size(),
                    PlanToSkeletonIntegrationHelper.extractExtrudedSolids(compiled).size()
            );

            // Step 3: ExecutableSkeletonPlan → Generator → BlockPatch
            // 使用 SkeletonExecutor 将每个 ExecutableSkeletonPlan 转换为 BlockPatch
            if (compiled.isEmpty() || world == null || globalAnchor == null) {
                FormacraftMod.LOGGER.debug("PlanProgramCompiler: skipping generator step (empty skeletons or missing world/anchor)");
                return List.of();
            }

            // 使用传递的风格 ID，如果没有则使用默认值
            String paletteId = (styleProfileId != null && !styleProfileId.isBlank()) 
                    ? styleProfileId 
                    : "DEFAULT";

            return generateBlockPatchesFromSkeletons(compiled.getSkeletons(), globalAnchor, world, paletteId);

        } catch (Exception e) {
            throw new SkeletonCompilationFailure("PlanSkeleton compilation failed", e);
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
                if (e1 instanceof SkeletonCompilationFailure failure) throw failure;
                // 尝试解析为 PlanProgram
                try {
                    PlanProgram planProgram = PlanProgramParser.parseAndValidate(json);
                    return compile(planProgram, globalAnchor, world);
                } catch (Exception e2) {
                    if (e2 instanceof SkeletonCompilationFailure failure) throw failure;
                    FormacraftMod.LOGGER.warn("PlanProgramCompiler: failed to parse as PlanSkeleton or PlanProgram", e2);
                    return List.of();
                }
            }
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("PlanProgramCompiler: JSON compilation failed", e);
            if (e instanceof SkeletonCompilationFailure failure) throw failure;
            return List.of();
        }
    }

    /**
     * 从 ExecutableSkeletonPlan 列表生成 BlockPatch 列表
     * <p>
     * 使用 SkeletonExecutor 将每个 skeleton 转换为 BlockPatch
     * <p>
     * 处理逻辑：
     * 1. 为每个 ExecutableSkeletonPlan 调用 Generator
     * 2. 所有 BlockPatch 使用相同的 origin（globalAnchor）
     * 3. 合并所有生成的 BlockPatch
     *
     * @param skeletons ExecutableSkeletonPlan 列表
     * @param origin 世界原点（BlockPatch 的相对坐标基准）
     * @param world 服务器世界
     * @param paletteId 调色板 ID（风格配置文件 ID）
     * @return BlockPatch 列表（相对 origin 的偏移）
     */
    private static List<BlockPatch> generateBlockPatchesFromSkeletons(
            List<ExecutableSkeletonPlan> skeletons,
            BlockPos origin,
            ServerWorld world,
            String paletteId
    ) {
        if (skeletons == null || skeletons.isEmpty() || world == null || origin == null) {
            return List.of();
        }

        // 确保 paletteId 不为空
        String effectivePaletteId = (paletteId != null && !paletteId.isBlank()) ? paletteId : "DEFAULT";

        return mergeSkeletonPatches(skeletons, skeleton -> SkeletonExecutors.get()
            .build(world, origin, skeleton, effectivePaletteId));
    }
}
