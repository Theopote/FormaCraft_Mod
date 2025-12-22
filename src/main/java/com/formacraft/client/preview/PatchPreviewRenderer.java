package com.formacraft.client.preview;

import com.formacraft.client.tool.ToolWorldRenderContext;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.util.math.Box;
import com.formacraft.client.preview.outline.OutlineQuad;
import net.minecraft.util.math.Direction;

import java.util.List;

/**
 * Patch 预览渲染（Greedy 外轮廓合并版）：
 * - place/replace：青绿色（外轮廓）
 * - remove：红色（外轮廓）
 * <p>
 * 不依赖 WorldRenderEvents，由我们的 Mixin 渲染注入点驱动。
 */
public final class PatchPreviewRenderer {
    private PatchPreviewRenderer() {}

    private static final double EPS = 0.02;

    public static void render(ToolWorldRenderContext ctx) {
        if (!PatchPreviewState.isEnabled()) return;

        List<OutlineQuad> place = PatchPreviewState.getPlaceOutline();
        List<OutlineQuad> replace = PatchPreviewState.getReplaceOutline();
        List<OutlineQuad> remove = PatchPreviewState.getRemoveOutline();
        List<OutlineQuad> rejected = PatchPreviewState.getRejectedOutline();

        // place：蓝色
        if (place != null) {
            for (OutlineQuad q : place) {
                Box box = toThinBox(q, ctx.cameraX, ctx.cameraY, ctx.cameraZ);
                VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, 0.25f, 0.75f, 1.00f, 0.85f);
            }
        }

        // replace：黄色
        if (replace != null) {
            for (OutlineQuad q : replace) {
                Box box = toThinBox(q, ctx.cameraX, ctx.cameraY, ctx.cameraZ);
                VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, 1.00f, 0.90f, 0.25f, 0.85f);
            }
        }

        // remove：红色
        if (remove != null) {
            for (OutlineQuad q : remove) {
                Box box = toThinBox(q, ctx.cameraX, ctx.cameraY, ctx.cameraZ);
                VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, 1.00f, 0.25f, 0.25f, 0.85f);
            }
        }

        // rejected：紫红（强提示：不会被执行）
        if (rejected != null) {
            for (OutlineQuad q : rejected) {
                Box box = toThinBox(q, ctx.cameraX, ctx.cameraY, ctx.cameraZ);
                VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, 0.90f, 0.20f, 1.00f, 0.95f);
            }
        }
    }

    /**
     * 把 OutlineQuad 转成“薄片 box”（用于复用 VertexRendering.drawBox 的线框绘制）。
     * 说明：薄片会画出两层非常接近的矩形边界，视觉上接近“面轮廓”，且 draw call 极少。
     */
    private static Box toThinBox(OutlineQuad q, double camX, double camY, double camZ) {
        Direction dir = q.dir();
        int d = q.d();
        int u0 = q.u0();
        int v0 = q.v0();
        int u1 = q.u1();
        int v1 = q.v1();

        double x1, y1, z1, x2, y2, z2;

        // 坐标映射：
        // Axis.X: plane x=d, u=z, v=y
        // Axis.Y: plane y=d, u=x, v=z
        // Axis.Z: plane z=d, u=x, v=y
        switch (dir.getAxis()) {
            case X -> {
                x1 = d - EPS;
                x2 = d + EPS;
                y1 = v0;
                y2 = v1;
                z1 = u0;
                z2 = u1;
            }
            case Y -> {
                y1 = d - EPS;
                y2 = d + EPS;
                x1 = u0;
                x2 = u1;
                z1 = v0;
                z2 = v1;
            }
            case Z -> {
                z1 = d - EPS;
                z2 = d + EPS;
                x1 = u0;
                x2 = u1;
                y1 = v0;
                y2 = v1;
            }
            default -> {
                x1 = 0; y1 = 0; z1 = 0; x2 = 0; y2 = 0; z2 = 0;
            }
        }

        return new Box(x1 - camX, y1 - camY, z1 - camZ, x2 - camX, y2 - camY, z2 - camZ);
    }
}

