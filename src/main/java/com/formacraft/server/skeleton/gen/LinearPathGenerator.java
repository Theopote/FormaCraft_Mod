package com.formacraft.server.skeleton.gen;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.skeleton.gen.util.GenMath;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LINEAR_PATH 生成器
 * 生成直线路径骨架
 */
public class LinearPathGenerator implements ISkeletonGenerator {

    @Override
    public List<BlockPatch> generate(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        // params:
        // - end: {dx,dy,dz}  (相对 origin)
        // - width: int (可选)
        // - block: "minecraft:white_concrete" (可选)
        String block = plan.get("block", "minecraft:white_concrete");

        Object endObj = plan.params.get("end");
        if (!(endObj instanceof Map<?, ?> m)) return List.of();

        int dx = toInt(m.get("dx"), 0);
        int dy = toInt(m.get("dy"), 0);
        int dz = toInt(m.get("dz"), 8);

        int width = Math.max(1, plan.get("width", 1));

        BlockPos a = ctx.origin;
        BlockPos b = ctx.origin.add(dx, dy, dz);

        List<BlockPos> spine = GenMath.line(a, b);
        List<BlockPatch> patches = new ArrayList<>();

        for (BlockPos p : spine) {
            // v1：把"宽度"理解成左右扩展（按 X 方向），后面可按 facing 正确扩展
            for (int w = 0; w < width; w++) {
                BlockPos q = p.add(w, 0, 0);
                BlockPos relative = q.subtract(ctx.origin);
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

