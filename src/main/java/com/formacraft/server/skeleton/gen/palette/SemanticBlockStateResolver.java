package com.formacraft.server.skeleton.gen.palette;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.semantic.SemanticPlacementOp;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Semantic → BlockState → BlockPatch 解析器
 * 
 * 使用 SemanticStyleProfile 和 PaletteRule 进行解析
 * 输出 BlockPatch（使用 BlockState 的 ID）
 */
public final class SemanticBlockStateResolver {

    private SemanticBlockStateResolver() {}

    /**
     * 将语义放置操作转换为 BlockPatch 列表（使用 BlockState）
     * 
     * @param origin 原点位置（用于计算相对坐标）
     * @param ops 语义放置操作列表
     * @param profileId 风格配置 ID
     * @param random 随机数生成器
     * @return BlockPatch 列表（相对 origin 的偏移）
     */
    public static List<BlockPatch> resolveToPatches(
            BlockPos origin,
            List<SemanticPlacementOp> ops,
            String profileId,
            Random random
    ) {
        SemanticPaletteResolver resolver = SemanticPaletteResolver.create(profileId, random);
        List<BlockPatch> patches = new ArrayList<>();
        if (ops == null || ops.isEmpty()) return patches;

        for (SemanticPlacementOp op : ops) {
            if (op == null || op.pos() == null || op.part() == null) continue;

            // 解析为 BlockState
            BlockState state = resolver.resolve(op);
            String blockId = blockStateToId(state);

            int dx = op.pos().getX() - origin.getX();
            int dy = op.pos().getY() - origin.getY();
            int dz = op.pos().getZ() - origin.getZ();

            // 使用 REPLACE 更安全
            patches.add(new BlockPatch(BlockPatch.REPLACE, dx, dy, dz, blockId));
        }

        return patches;
    }

    /**
     * 将 BlockState 转换为方块 ID 字符串
     */
    private static String blockStateToId(BlockState state) {
        if (state == null) return "minecraft:stone";
        Block block = state.getBlock();
        Identifier id = Registries.BLOCK.getId(block);
        return id != null ? id.toString() : "minecraft:stone";
    }
}

