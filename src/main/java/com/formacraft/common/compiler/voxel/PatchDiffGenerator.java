package com.formacraft.common.compiler.voxel;

import com.formacraft.common.patch.BlockPatch;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * PatchDiffGenerator：将 VoxelPlan 转换为 BlockPatch 列表。
 * <p>
 * 核心思想：
 * - 比较 VoxelPlan 中的语义块与世界现状
 * - 生成差异（diff）：place / replace / remove
 * - 仅描述差异（Patch），不直接 setBlock
 * <p>
 * 这是最关键的一步，将"语义计划"转换为"可执行的增量修改"。
 */
public final class PatchDiffGenerator {
    private PatchDiffGenerator() {}

    /**
     * 生成 BlockPatch 列表（差异）
     * 
     * @param origin 世界坐标原点（anchor 位置）
     * @param plan 体素计划
     * @param world 世界视图（用于读取当前方块状态）
     * @param styleProfileId 风格配置 ID（用于 PaletteResolver）
     * @param rng 随机数生成器（用于 PaletteResolver 的随机选择）
     * @return BlockPatch 列表（相对 origin 的偏移）
     */
    public static List<BlockPatch> diff(
            BlockPos origin,
            VoxelPlan plan,
            WorldView world,
            String styleProfileId,
            Random rng
    ) {
        List<BlockPatch> patches = new ArrayList<>();

        if (plan == null || plan.isEmpty() || origin == null) {
            return patches;
        }

        if (world == null) {
            // 如果没有世界，生成所有 place 操作（用于预览）
            for (var entry : plan.blocks().entrySet()) {
                VoxelPlan.Vec3i d = entry.getKey();
                SemanticBlock sb = entry.getValue();

                BlockState target = PaletteResolver.resolve(sb, styleProfileId, rng);
                String blockId = blockStateToId(target);
                patches.add(new BlockPatch(BlockPatch.PLACE, d.x(), d.y(), d.z(), blockId));
            }
            return patches;
        }

        // 有世界：生成差异
        for (var entry : plan.blocks().entrySet()) {
            VoxelPlan.Vec3i d = entry.getKey();
            SemanticBlock sb = entry.getValue();

            BlockPos pos = origin.add(d.x(), d.y(), d.z());
            BlockState current = world.getBlockState(pos);
            BlockState target = PaletteResolver.resolve(sb, styleProfileId, rng);

            // 确定操作类型
            String action;
            String blockId;

            if (current.isAir()) {
                // 当前位置是空气 → place
                action = BlockPatch.PLACE;
                blockId = blockStateToId(target);
            } else if (!current.equals(target)) {
                // 当前位置有方块且不同 → replace
                action = BlockPatch.REPLACE;
                blockId = blockStateToId(target);
            } else {
                // 当前位置已经是目标方块 → 跳过（不生成 patch）
                continue;
            }

            patches.add(new BlockPatch(action, d.x(), d.y(), d.z(), blockId));
        }

        return patches;
    }

    /**
     * 生成 BlockPatch 列表（使用默认风格配置）
     */
    public static List<BlockPatch> diff(
            BlockPos origin,
            VoxelPlan plan,
            WorldView world,
            Random rng
    ) {
        return diff(origin, plan, world, null, rng);
    }

    /**
     * 将 BlockState 转换为 block ID 字符串
     * <p>
     * 格式：minecraft:block_id[property1=value1,property2=value2]
     */
    private static String blockStateToId(BlockState state) {
        if (state == null) {
            return "minecraft:stone";
        }

        var block = state.getBlock();
        var id = net.minecraft.registry.Registries.BLOCK.getId(block);
        
        // 简化：只返回 block ID，不包含状态属性
        // 如果需要完整的状态字符串，可以添加更复杂的逻辑
        return id.toString();
    }
}
