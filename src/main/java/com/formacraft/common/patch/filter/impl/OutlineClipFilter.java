package com.formacraft.common.patch.filter.impl;

import com.formacraft.client.tool.OutlineMode;
import com.formacraft.client.tool.OutlineTool;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.filter.PatchFilter;
import com.formacraft.common.patch.filter.PatchFilterContext;

import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.stream.Collectors;

/**
 * OutlineClipFilter（轮廓裁切）
 * 
 * 核心功能：只保留位于轮廓内的 BlockPatch
 * 
 * 效果：
 * 🟪 AI 建筑被"剪"进轮廓
 * 再复杂的 Generator 都服从
 */
public class OutlineClipFilter implements PatchFilter {

    @Override
    public List<BlockPatch> filter(
            List<BlockPatch> input,
            BlockPos origin,
            PatchFilterContext context
    ) {
        if (!context.hasOutline()) {
            return input;
        }

        OutlineTool outline = context.outline;
        OutlineTool.OutlineShape shape = outline.getShape();

        if (shape == null) {
            return input;
        }

        return input.stream()
                .filter(p -> {
                    BlockPos pos = origin.add(p.dx(), p.dy(), p.dz());
                    return contains(shape, pos);
                })
                .collect(Collectors.toList());
    }

    /**
     * 检查位置是否在轮廓内
     */
    private boolean contains(OutlineTool.OutlineShape shape, BlockPos pos) {
        if (shape == null || pos == null) {
            return false;
        }

        // 高度范围检查
        int y = pos.getY();
        if (y < shape.minY() || y > shape.maxY()) {
            return false;
        }

        // 圆形检查
        if (shape.mode() == OutlineMode.CIRCLE && shape.center() != null) {
            int dx = pos.getX() - shape.center().getX();
            int dz = pos.getZ() - shape.center().getZ();
            return (dx * dx + dz * dz) <= (shape.radius() * shape.radius());
        }

        // 多边形检查（使用射线法）
        List<BlockPos> points = shape.points();
        if (points == null || points.size() < 3) {
            return false;
        }

        return pointInPolygonXZ(pos.getX() + 0.5, pos.getZ() + 0.5, points);
    }

    /**
     * 射线法判断点是否在多边形内（XZ 平面）
     */
    private static boolean pointInPolygonXZ(double x, double z, List<BlockPos> poly) {
        boolean inside = false;
        int n = poly.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = poly.get(i).getX() + 0.5;
            double zi = poly.get(i).getZ() + 0.5;
            double xj = poly.get(j).getX() + 0.5;
            double zj = poly.get(j).getZ() + 0.5;

            boolean intersect = ((zi > z) != (zj > z)) &&
                    (x < (xj - xi) * (z - zi) / (zj - zi + 0.0) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }
}

