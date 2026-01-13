package com.formacraft.client.preview;

import com.formacraft.client.tool.ToolRenderUtil;
import com.formacraft.client.tool.ToolWorldRenderContext;
import com.formacraft.common.component.transform.ComponentTransform;
import com.formacraft.common.component.transform.ComponentTransformUtil;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.List;

/**
 * Component 预览渲染（线框 + 锚点 + facing 指示）。
 * <p>
 * 注意：复用 lines RenderLayer（由 SelectionBoxRenderMixin 注入点驱动）。
 */
public final class ComponentPreviewRenderer {
    private ComponentPreviewRenderer() {}

    private static final int MAX_BLOCKS = 1800;

    public static void render(ToolWorldRenderContext ctx) {
        if (ctx == null) return;
        if (!ComponentPreviewState.isActive()) return;

        BlockPos anchor = ComponentPreviewState.getWorldAnchor();
        List<BlockPos> local = ComponentPreviewState.getLocalBlocks();
        Direction fromFacing = ComponentPreviewState.getFromFacing();
        ComponentTransform t = ComponentPreviewState.getTransform();
        float r = ComponentPreviewState.getR();
        float g = ComponentPreviewState.getG();
        float b = ComponentPreviewState.getB();
        float a = ComponentPreviewState.getA();
        if (anchor == null || local == null || local.isEmpty()) return;

        // 1) 体量（AABB 外框）
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : local) {
            if (p == null) continue;
            BlockPos tp = ComponentTransformUtil.transformOffset(p, fromFacing, t);
            minX = Math.min(minX, tp.getX());
            minY = Math.min(minY, tp.getY());
            minZ = Math.min(minZ, tp.getZ());
            maxX = Math.max(maxX, tp.getX());
            maxY = Math.max(maxY, tp.getY());
            maxZ = Math.max(maxZ, tp.getZ());
        }
        if (minX != Integer.MAX_VALUE) {
            Box world = new Box(
                    anchor.getX() + minX, anchor.getY() + minY, anchor.getZ() + minZ,
                    anchor.getX() + maxX + 1, anchor.getY() + maxY + 1, anchor.getZ() + maxZ + 1
            ).expand(0.01);
            Box box = world.offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);
            VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, r, g, b, Math.min(0.95f, a * 0.85f));
        }

        // 2) 方块线框（采样，避免超大选区卡顿）
        int n = local.size();
        int step = Math.max(1, (int) Math.ceil(n / (double) MAX_BLOCKS));
        for (int i = 0; i < n; i += step) {
            BlockPos lp = local.get(i);
            if (lp == null) continue;
            BlockPos tp = ComponentTransformUtil.transformOffset(lp, fromFacing, t);
            int wx = anchor.getX() + tp.getX();
            int wy = anchor.getY() + tp.getY();
            int wz = anchor.getZ() + tp.getZ();
            Box world = new Box(wx, wy, wz, wx + 1, wy + 1, wz + 1).expand(0.01);
            Box box = world.offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);
            VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, r, g, b, a);
        }

        // 3) Anchor 标记（小方块 + 十字）
        Box aw = new Box(
                anchor.getX() + 0.20, anchor.getY() + 0.20, anchor.getZ() + 0.20,
                anchor.getX() + 0.80, anchor.getY() + 0.80, anchor.getZ() + 0.80
        );
        VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer,
                aw.offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ),
                1.00f, 0.85f, 0.20f, 0.95f);

        double cx = anchor.getX() + 0.5;
        double cy = anchor.getY() + 0.5;
        double cz = anchor.getZ() + 0.5;
        double s = 0.65;
        ToolRenderUtil.line(ctx, cx - s, cy, cz, cx + s, cy, cz, 255, 220, 80, 220);
        ToolRenderUtil.line(ctx, cx, cy - s, cz, cx, cy + s, cz, 255, 220, 80, 220);
        ToolRenderUtil.line(ctx, cx, cy, cz - s, cx, cy, cz + s, 255, 220, 80, 220);

        // 4) Facing 箭头（从 anchor 指向 facing）
        Direction facing = (t != null) ? t.facing() : null;
        if (facing != null) {
            double ex = cx + facing.getOffsetX() * 1.6;
            double ez = cz + facing.getOffsetZ() * 1.6;
            ToolRenderUtil.line(ctx, cx, cy + 0.15, cz, ex, cy + 0.15, ez, 160, 255, 120, 240);
            // 简单箭头头部（两条小斜线）
            double hx = cx + facing.getOffsetX() * 1.35;
            double hz = cz + facing.getOffsetZ() * 1.35;
            Direction left = facing.rotateYCounterclockwise();
            Direction right = facing.rotateYClockwise();
            ToolRenderUtil.line(ctx, ex, cy + 0.15, ez, hx + left.getOffsetX() * 0.35, cy + 0.15, hz + left.getOffsetZ() * 0.35, 160, 255, 120, 240);
            ToolRenderUtil.line(ctx, ex, cy + 0.15, ez, hx + right.getOffsetX() * 0.35, cy + 0.15, hz + right.getOffsetZ() * 0.35, 160, 255, 120, 240);
        }
    }
}

