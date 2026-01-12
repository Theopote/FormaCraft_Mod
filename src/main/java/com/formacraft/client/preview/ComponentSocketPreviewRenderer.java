package com.formacraft.client.preview;

import com.formacraft.client.tool.ToolRenderUtil;
import com.formacraft.client.tool.ToolWorldRenderContext;
import com.formacraft.common.component.transform.ComponentTransform;
import com.formacraft.common.component.transform.ComponentTransformUtil;
import com.formacraft.common.component.transform.FacingTransformUtil;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

/**
 * Socket 预览渲染（mask AABB + facing 指示）。
 */
public final class ComponentSocketPreviewRenderer {
    private ComponentSocketPreviewRenderer() {}

    public static void render(ToolWorldRenderContext ctx) {
        if (ctx == null) return;
        if (!ComponentSocketPreviewState.isActive()) return;

        BlockPos anchor = ComponentSocketPreviewState.getAnchorWorld();
        BlockPos origin = ComponentSocketPreviewState.getSocketOriginLocal();
        if (anchor == null || origin == null) return;

        int w = ComponentSocketPreviewState.getW();
        int h = ComponentSocketPreviewState.getH();
        int d = ComponentSocketPreviewState.getD();
        Direction fromFacing = ComponentSocketPreviewState.getFromFacing();
        ComponentTransform t = ComponentSocketPreviewState.getTransform();

        // 8 个角点：计算变换后的 AABB（镜像/旋转可能导致 min/max 交换）
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (int ox : new int[]{0, w}) {
            for (int oy : new int[]{0, h}) {
                for (int oz : new int[]{0, d}) {
                    BlockPos p = new BlockPos(origin.getX() + ox, origin.getY() + oy, origin.getZ() + oz);
                    BlockPos tp = ComponentTransformUtil.transformOffset(p, fromFacing, t);
                    minX = Math.min(minX, tp.getX());
                    minY = Math.min(minY, tp.getY());
                    minZ = Math.min(minZ, tp.getZ());
                    maxX = Math.max(maxX, tp.getX());
                    maxY = Math.max(maxY, tp.getY());
                    maxZ = Math.max(maxZ, tp.getZ());
                }
            }
        }
        if (minX == Integer.MAX_VALUE) return;

        // AABB：半透明青色
        Box world = new Box(
                anchor.getX() + minX, anchor.getY() + minY, anchor.getZ() + minZ,
                anchor.getX() + maxX, anchor.getY() + maxY, anchor.getZ() + maxZ
        ).expand(0.01);
        Box box = world.offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);
        VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, 0.20f, 0.95f, 0.90f, 0.70f);

        // facing arrow：以 socket local facing 经 transform 后的方向显示
        Direction localFacing = ComponentSocketPreviewState.getSocketFacingLocal();
        Direction facing = FacingTransformUtil.transformFacing(localFacing, fromFacing, t);
        if (facing != null) {
            double cx = (world.minX + world.maxX) * 0.5;
            double cy = (world.minY + world.maxY) * 0.5;
            double cz = (world.minZ + world.maxZ) * 0.5;
            double ex = cx + facing.getOffsetX() * 1.6;
            double ez = cz + facing.getOffsetZ() * 1.6;
            ToolRenderUtil.line(ctx, cx, cy, cz, ex, cy, ez, 90, 255, 255, 235);
        }
    }
}

