package com.formacraft.server.skeleton.gen;

import com.formacraft.common.skeleton.ExecutableSkeletonPlan;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.skeleton.gen.util.GenMath;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PATH_POLYLINE 生成器
 * 生成折线路径骨架
 */
public class PathPolylineGenerator implements ISkeletonGenerator {

    @Override
    public List<BlockPatch> generate(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        // params:
        // - points: [ {dx,dy,dz}, ... ] 相对 origin 的折线点（至少2个）
        // - block: string
        String block = plan.get("block", "minecraft:yellow_concrete");

        Object ptsObj = plan.params.get("points");
        if (!(ptsObj instanceof List<?> pts) || pts.size() < 2) return List.of();

        List<BlockPos> abs = new ArrayList<>();
        for (Object o : pts) {
            if (!(o instanceof Map<?, ?> m)) continue;
            int dx = toInt(m.get("dx"), 0);
            int dy = toInt(m.get("dy"), 0);
            int dz = toInt(m.get("dz"), 0);
            abs.add(ctx.origin.add(dx, dy, dz));
        }
        if (abs.size() < 2) return List.of();

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

