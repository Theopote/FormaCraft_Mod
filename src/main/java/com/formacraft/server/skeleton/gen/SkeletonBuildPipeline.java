package com.formacraft.server.skeleton.gen;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.semantic.SemanticPlacementOp;

import java.util.List;

/**
 * Skeleton 建造流程
 * 
 * 完整闭环：Skeleton → SemanticOps → Patch
 * 
 * 这是接 AI、Preview、Apply 的关键 glue
 */
public final class SkeletonBuildPipeline {

    private SkeletonBuildPipeline() {}

    /**
     * 将 Skeleton 构建为 BlockPatch 列表
     * 
     * @param ctx 生成上下文
     * @param plan 可执行的骨架计划
     * @param paletteId 调色板 ID
     * @param patchOrigin Patch 原点（用于计算相对坐标）
     * @return BlockPatch 列表（相对 patchOrigin 的偏移）
     */
    public static List<BlockPatch> buildSkeletonAsPatch(
            GenerationContext ctx,
            ExecutableSkeletonPlan plan,
            String paletteId,
            net.minecraft.util.math.BlockPos patchOrigin
    ) {
        if (ctx == null || plan == null || patchOrigin == null) {
            return List.of();
        }

        var gen = SkeletonSemanticRegistry.get(plan.type);
        if (gen == null) return List.of();

        List<SemanticPlacementOp> semanticOps = gen.generateSemantic(ctx, plan);

        // patchOrigin：建议用 anchor / selectionOrigin
        return SemanticResolver.resolveToPatches(patchOrigin, semanticOps, paletteId, ctx.random);
    }
}

