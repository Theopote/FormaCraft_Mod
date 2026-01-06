package com.formacraft.server.skeleton.gen;

import com.formacraft.common.palette.SemanticPalette;
import com.formacraft.common.palette.SemanticPaletteRegistry;
import com.formacraft.common.palette.WeightedPicker;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.semantic.SemanticPlacementOp;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 语义解析器
 * 
 * 把 SemanticPlacementOp → BlockPatch
 * 
 * 输入：origin + semanticOps + paletteId
 * 输出：List<BlockPatch>
 */
public final class SemanticResolver {

    private SemanticResolver() {}

    /**
     * 将语义放置操作转换为 BlockPatch 列表
     * 
     * @param origin 原点位置（用于计算相对坐标）
     * @param ops 语义放置操作列表
     * @param paletteId 调色板 ID
     * @param random 随机数生成器
     * @return BlockPatch 列表（相对 origin 的偏移）
     */
    public static List<BlockPatch> resolveToPatches(
            BlockPos origin,
            List<SemanticPlacementOp> ops,
            String paletteId,
            Random random
    ) {
        SemanticPalette palette = SemanticPaletteRegistry.getOrDefault(paletteId);
        List<BlockPatch> patches = new ArrayList<>();
        if (ops == null || ops.isEmpty()) return patches;

        for (SemanticPlacementOp op : ops) {
            if (op == null || op.pos() == null || op.part() == null) continue;

            var blocks = palette.get(op.part(), op.role());
            String blockId = WeightedPicker.pick(random, blocks, "minecraft:stone");

            int dx = op.pos().getX() - origin.getX();
            int dy = op.pos().getY() - origin.getY();
            int dz = op.pos().getZ() - origin.getZ();

            // 使用 REPLACE 更安全（不会在已有方块上放置）
            patches.add(new BlockPatch(BlockPatch.REPLACE, dx, dy, dz, blockId));
        }

        return patches;
    }
}

