package com.formacraft.server.skeleton.gen;

import com.formacraft.common.component.ComponentPlan;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.semantic.SemanticPlacementOp;
import com.formacraft.server.skeleton.gen.assembler.ComponentAssemblyPipeline;

import java.util.List;

/**
 * 组件建造流程（完整闭环）
 * 
 * 最终完整闭环：
 * LLM
 *  └─ SkeletonPlan
 *  └─ ComponentPlan
 *         ↓
 * ComponentAssemblyPipeline
 *         ↓
 * SemanticPlacementOps
 *         ↓
 * SemanticResolver + Palette
 *         ↓
 * BlockPatch
 *         ↓
 * Preview / Diff / Apply
 */
public final class ComponentBuildPipeline {

    private ComponentBuildPipeline() {}

    /**
     * 将 Skeleton + Component 构建为 BlockPatch 列表
     * 
     * @param ctx 生成上下文
     * @param skeleton 骨架计划
     * @param components 组件计划
     * @param paletteId 调色板 ID
     * @param patchOrigin Patch 原点（用于计算相对坐标）
     * @return BlockPatch 列表（相对 patchOrigin 的偏移）
     */
    public static List<BlockPatch> buildComponentAsPatch(
            GenerationContext ctx,
            ExecutableSkeletonPlan skeleton,
            ComponentPlan components,
            String paletteId,
            net.minecraft.util.math.BlockPos patchOrigin
    ) {
        if (ctx == null || skeleton == null || patchOrigin == null) {
            return List.of();
        }

        // 如果没有组件，回退到直接生成 Skeleton
        if (components == null || components.isEmpty()) {
            return SkeletonBuildPipeline.buildSkeletonAsPatch(ctx, skeleton, paletteId, patchOrigin);
        }

        // 装配所有组件，生成语义操作
        List<SemanticPlacementOp> semanticOps = ComponentAssemblyPipeline.assembleAll(
                ctx, skeleton, components
        );

        // 解析语义操作为 BlockPatch
        return SemanticResolver.resolveToPatches(patchOrigin, semanticOps, paletteId, ctx.random);
    }
}

