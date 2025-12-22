package com.formacraft.client.tool;

import net.minecraft.client.render.VertexRendering;
import net.minecraft.util.math.Box;

/**
 * 世界渲染工具：用“极薄盒子线框”近似线段，避免直接写 VertexConsumer 顶点（不同 MC 版本 API 差异大）。
 */
public final class ToolRenderUtil {
    private ToolRenderUtil() {}

    public static void line(ToolWorldRenderContext ctx,
                            double x1, double y1, double z1,
                            double x2, double y2, double z2,
                            int r, int g, int b, int a) {
        if (ctx == null) return;
        if (ctx.vertexConsumer == null) return;

        float rf = r / 255f;
        float gf = g / 255f;
        float bf = b / 255f;
        float af = a / 255f;

        // 1) 轴对齐的线段：用“极薄长盒子”的线框模拟一条线（更省）
        double eps = 1e-6;
        double thickness = 0.02;
        if (Math.abs(y1 - y2) < eps && Math.abs(z1 - z2) < eps) {
            // X 方向
            double minX = Math.min(x1, x2);
            double maxX = Math.max(x1, x2);
            Box world = new Box(minX, y1 - thickness, z1 - thickness, maxX, y1 + thickness, z1 + thickness).expand(0.001);
            Box box = world.offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);
            VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, rf, gf, bf, af);
            return;
        }
        if (Math.abs(y1 - y2) < eps && Math.abs(x1 - x2) < eps) {
            // Z 方向
            double minZ = Math.min(z1, z2);
            double maxZ = Math.max(z1, z2);
            Box world = new Box(x1 - thickness, y1 - thickness, minZ, x1 + thickness, y1 + thickness, maxZ).expand(0.001);
            Box box = world.offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);
            VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, rf, gf, bf, af);
            return;
        }
        if (Math.abs(x1 - x2) < eps && Math.abs(z1 - z2) < eps) {
            // Y 方向
            double minY = Math.min(y1, y2);
            double maxY = Math.max(y1, y2);
            Box world = new Box(x1 - thickness, minY, z1 - thickness, x1 + thickness, maxY, z1 + thickness).expand(0.001);
            Box box = world.offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);
            VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, rf, gf, bf, af);
            return;
        }

        // 2) 非轴对齐：用若干个小点（小盒子）插值拼成线段（v1 足够直观）
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        int steps = (int) Math.max(8, Math.ceil(Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) * 2.0));
        double half = 0.015;
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double x = x1 + dx * t;
            double y = y1 + dy * t;
            double z = z1 + dz * t;
            Box world = new Box(x - half, y - half, z - half, x + half, y + half, z + half);
            Box box = world.offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);
            VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, rf, gf, bf, af);
        }
    }
}


