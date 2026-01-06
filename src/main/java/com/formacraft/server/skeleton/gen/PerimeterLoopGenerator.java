package com.formacraft.server.skeleton.gen;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.skeleton.gen.util.GenMath;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PERIMETER_LOOP 生成器
 * 生成轮廓闭环骨架
 */
public class PerimeterLoopGenerator implements ISkeletonGenerator {
    @Override
    public List<BlockPatch> generate(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        // params:
        // - points: [ {dx,dy,dz}, ... ] 轮廓点（必须闭合）
        // - block: string
        String block = plan.get("block", "minecraft:blue_concrete");

        Object ptsObj = plan.params.get("points");
        if (!(ptsObj instanceof List<?> pts) || pts.size() < 3) return List.of();

        List<BlockPos> abs = new ArrayList<>();
        for (Object o : pts) {
            if (!(o instanceof Map<?, ?> m)) continue;
            int dx = toInt(m.get("dx"), 0);
            int dy = toInt(m.get("dy"), 0);
            int dz = toInt(m.get("dz"), 0);
            abs.add(ctx.origin.add(dx, dy, dz));
        }
        if (abs.size() < 3) return List.of();

        // 确保闭合：如果最后一个点不等于第一个点，添加第一个点
        if (!abs.get(abs.size() - 1).equals(abs.get(0))) {
            abs.add(abs.get(0));
        }

        List<BlockPatch> patches = new ArrayList<>();
        for (int i = 0; i < abs.size() - 1; i++) {
            for (BlockPos p : GenMath.line(abs.get(i), abs.get(i + 1))) {
                BlockPos relative = p.subtract(ctx.origin);
                patches.add(new BlockPatch(BlockPatch.PLACE, relative.getX(), relative.getY(), relative.getZ(), block));
                if (patches.size() >= ctx.maxOps) return patches;
            }
        }
        return patches;
    }

    private static int toInt(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return def; }
    }
}

