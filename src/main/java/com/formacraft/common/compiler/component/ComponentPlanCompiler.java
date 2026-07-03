package com.formacraft.common.compiler.component;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.model.PersistedComponentVariant;
import com.formacraft.common.compiler.voxel.*;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.placement.PlacementContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldView;

import java.util.List;
import java.util.Random;

/**
 * ComponentPlanCompiler（组件计划编译器）：Component → Patch 编译器。
 * <p>
 * 支持两种变体类型：
 * - 存盘变体文档 {@link PersistedComponentVariant}
 * - 运行时变体 {@link com.formacraft.common.component.variant.ComponentVariant}（经 {@link ComponentVariantAdapter} 适配）
 * <p>
 * 核心原则：
 * - ❌ 绝不直接 setBlock
 * - ✅ 仅描述差异（Patch）
 * - ✅ 可用于 Preview / Apply / Undo / Redo / Memory Update
 * <p>
 * 完整流程：
 * ComponentDefinition → ComponentVoxelizer → VoxelPlan →
 * PatchDiffGenerator → List&lt;BlockPatch&gt;
 */
public final class ComponentPlanCompiler {
    private ComponentPlanCompiler() {}

    /**
     * 编译 ComponentDefinition 为 BlockPatch 列表（主入口）
     * 
     * @param component 构件定义（已验证）
     * @param variant 构件变体（已解析/确定，可选）
     * @param ctx 放置上下文（anchor / facing / surface / tool 约束）
     * @param world 世界视图（用于生成差异，可选）
     * @param styleProfileId 风格配置 ID（用于 PaletteResolver，可选）
     * @return BlockPatch 列表（相对 ctx.targetPos 的偏移）
     */
    public static List<BlockPatch> compile(
            ComponentDefinition component,
            PersistedComponentVariant variant,
            PlacementContext ctx,
            WorldView world,
            String styleProfileId
    ) {
        if (component == null) {
            return List.of();
        }

        // 1. 生成组件的逻辑体素计划
        VoxelPlan plan = ComponentVoxelizer.voxelize(component, variant, ctx);

        if (plan.isEmpty()) {
            return List.of();
        }

        // 2. 根据世界现状生成 Patch Diff
        BlockPos origin = ctx != null ? ctx.targetPos : BlockPos.ORIGIN;
        Random rng = new Random(); // WorldView 没有 getRandom()，使用默认 Random

        return PatchDiffGenerator.diff(origin, plan, world, styleProfileId, rng);
    }

    /**
     * 编译 ComponentDefinition 为 BlockPatch 列表（简化版本，不使用风格配置）
     */
    public static List<BlockPatch> compile(
            ComponentDefinition component,
            PersistedComponentVariant variant,
            PlacementContext ctx,
            WorldView world
    ) {
        return compile(component, variant, ctx, world, null);
    }

    /**
     * 编译 ComponentDefinition 为 BlockPatch 列表（最简版本，仅用于预览）
     */
    public static List<BlockPatch> compile(
            ComponentDefinition component,
            PersistedComponentVariant variant,
            PlacementContext ctx
    ) {
        return compile(component, variant, ctx, null, null);
    }

    /**
     * 编译 ComponentDefinition 为 BlockPatch 列表（使用新的 ComponentVariant）
     * 
     * @param component 构件定义（已验证）
     * @param newVariant 新的构件变体（com.formacraft.common.component.variant.ComponentVariant）
     * @param ctx 放置上下文
     * @param world 世界视图
     * @param styleProfileId 风格配置 ID
     * @return BlockPatch 列表
     */
    public static List<BlockPatch> compileWithNewVariant(
            ComponentDefinition component,
            com.formacraft.common.component.variant.ComponentVariant newVariant,
            PlacementContext ctx,
            WorldView world,
            String styleProfileId
    ) {
        if (component == null) {
            return List.of();
        }

        // 运行时变体 → 存盘 schema，再走 voxel 编译管线
        PersistedComponentVariant persistedVariant = ComponentVariantAdapter.adapt(newVariant, component);

        return compile(component, persistedVariant, ctx, world, styleProfileId);
    }
}
