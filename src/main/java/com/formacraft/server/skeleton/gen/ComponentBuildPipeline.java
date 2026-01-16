package com.formacraft.server.skeleton.gen;

import com.formacraft.common.assembly.AutoAssembler;
import com.formacraft.common.assembly.SkeletonComponentRules;
import com.formacraft.common.assembly.SkeletonSocketGenerator;
import com.formacraft.common.component.ComponentPlan;
import com.formacraft.common.component.socket.Socket;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.semantic.SemanticPlacementOp;
import com.formacraft.common.style.SemanticStyleProfile;
import com.formacraft.common.style.SemanticStyleProfileRegistry;
import com.formacraft.server.skeleton.gen.assembler.ComponentAssemblyPipeline;
import com.formacraft.server.skeleton.gen.geometry.GeometryModifierPipeline;
import com.formacraft.server.skeleton.gen.palette.SemanticBlockStateResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * Component 建造流程（完整闭环）
 * 
 * 最终完整闭环：
 * LLM
 *  └─ SkeletonPlan
 *  └─ ComponentPlan
 *         ↓
 * ComponentAssemblyPipeline
 *         ↓
 * SemanticPlacementOps（基础）
 *         ↓
 * GeometryModifierPipeline（几何修饰）
 *         ↓
 * SemanticBlockStateResolver + Palette
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
     * @param paletteId 调色板 ID（也用作 styleProfileId）
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

        // 1. 装配所有组件，生成基础语义操作
        List<SemanticPlacementOp> baseOps = ComponentAssemblyPipeline.assembleAll(
                ctx, skeleton, components
        );

        // 2. 应用几何修饰器（如果有风格配置）
        SemanticStyleProfile style = SemanticStyleProfileRegistry.getOrDefault(paletteId);
        List<SemanticPlacementOp> expandedOps = GeometryModifierPipeline.applyModifiers(baseOps, style);

        // 3. 解析为 BlockPatch（使用 BlockState 解析器）
        List<BlockPatch> skeletonPatches = SemanticBlockStateResolver.resolveToPatches(
                patchOrigin, expandedOps, paletteId, ctx.random
        );

        // 4. 自动装配细节（使用 AutoAssembler）
        // 从骨架生成 Socket，然后自动装配构件
        List<BlockPatch> detailPatches = autoAssembleDetails(
                ctx, skeleton, paletteId, patchOrigin
        );

        // 5. 合并所有 Patch
        List<BlockPatch> allPatches = new ArrayList<>(skeletonPatches);
        allPatches.addAll(detailPatches);

        return allPatches;
    }

    /**
     * 自动装配细节（使用 AutoAssembler）
     * 
     * @param ctx 生成上下文
     * @param skeleton 骨架计划
     * @param styleProfile 风格配置 ID
     * @param origin 原点位置
     * @return BlockPatch 列表（细节构件）
     */
    private static List<BlockPatch> autoAssembleDetails(
            GenerationContext ctx,
            ExecutableSkeletonPlan skeleton,
            String styleProfile,
            net.minecraft.util.math.BlockPos origin
    ) {
        List<BlockPatch> patches = new ArrayList<>();

        if (ctx == null || skeleton == null || ctx.world == null) {
            return patches;
        }

        try {
            // 1. 从骨架生成 Socket
            List<Socket> sockets = SkeletonSocketGenerator.generateSockets(
                    skeleton, ctx.world, origin
            );

            if (sockets.isEmpty()) {
                return patches;
            }

            // 2. 获取规则（默认中世纪规则）
            SkeletonComponentRules rules = SkeletonComponentRules.defaultMedieval();

            // 3. 推断骨架类型（从 SkeletonType 转换为字符串）
            String skeletonKind = skeleton.type != null ? skeleton.type.name() : "GENERIC";

            // 4. 自动装配并编译为 Patch
            patches = AutoAssembler.assembleAndCompile(
                    sockets,
                    rules,
                    skeletonKind,
                    styleProfile,
                    "default", // materialTone
                    ctx.world,
                    ctx.random
            );
        } catch (Exception e) {
            // v1：如果自动装配失败，不影响主流程
            // FormacraftMod.LOGGER.warn("AutoAssembler failed: " + e.getMessage());
        }

        return patches;
    }
}

