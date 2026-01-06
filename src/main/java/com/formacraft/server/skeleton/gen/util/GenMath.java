package com.formacraft.server.skeleton.gen.util;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 生成器数学工具类
 */
public final class GenMath {
    private GenMath() {}

    /**
     * 3D 直线离散（Bresenham 简化版：按步长插值）
     */
    public static List<BlockPos> line(BlockPos a, BlockPos b) {
        int dx = b.getX() - a.getX();
        int dy = b.getY() - a.getY();
        int dz = b.getZ() - a.getZ();

        int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        steps = Math.max(steps, 1);

        List<BlockPos> out = new ArrayList<>(steps + 1);
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            int x = a.getX() + (int) Math.round(dx * t);
            int y = a.getY() + (int) Math.round(dy * t);
            int z = a.getZ() + (int) Math.round(dz * t);
            out.add(new BlockPos(x, y, z));
        }
        return out;
    }

    /**
     * 2D 圆环离散（在 XZ 平面）
     */
    public static List<BlockPos> circleXZ(BlockPos center, int radius) {
        List<BlockPos> out = new ArrayList<>();
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();
        int r = Math.max(1, radius);

        // 简化：按角度采样
        int samples = Math.max(24, r * 12);
        for (int i = 0; i < samples; i++) {
            double ang = (Math.PI * 2.0) * (i / (double) samples);
            int x = cx + (int) Math.round(Math.cos(ang) * r);
            int z = cz + (int) Math.round(Math.sin(ang) * r);
            out.add(new BlockPos(x, cy, z));
        }
        return out;
    }
}

