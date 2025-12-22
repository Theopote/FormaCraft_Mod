package com.formacraft.common.buildcontext;

import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * 轮廓形状（XZ 平面）。
 */
public record OutlineShape(
        String shapeType,            // "polygon" / "circle"
        List<BlockPos> vertices,     // polygon 顶点（世界坐标；主要用 X/Z）
        BlockPos center,             // circle 圆心
        int radius,                  // circle 半径
        int minY,
        int maxY
) {
    public BlockPos computeCenterOrigin() {
        if ("circle".equalsIgnoreCase(shapeType) && center != null) {
            return new BlockPos(center.getX(), minY, center.getZ());
        }
        if (vertices == null || vertices.isEmpty()) return null;
        double sx = 0, sz = 0;
        for (BlockPos p : vertices) {
            sx += p.getX() + 0.5;
            sz += p.getZ() + 0.5;
        }
        int cx = (int) Math.floor(sx / vertices.size());
        int cz = (int) Math.floor(sz / vertices.size());
        return new BlockPos(cx, minY, cz);
    }
}


