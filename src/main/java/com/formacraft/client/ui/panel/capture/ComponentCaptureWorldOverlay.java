package com.formacraft.client.ui.panel.capture;

import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.tool.SelectionTool;
import com.formacraft.client.tool.ToolRenderUtil;
import com.formacraft.client.tool.ToolWorldRenderContext;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

/**
 * 构件捕获面板的世界叠加渲染：选区、锚点、方向标记、宿主面。
 */
public final class ComponentCaptureWorldOverlay {
    private final ComponentCaptureSelectionController selectionController;

    public ComponentCaptureWorldOverlay(ComponentCaptureSelectionController selectionController) {
        this.selectionController = selectionController;
    }

    public void render(ToolWorldRenderContext ctx) {
        SelectionTool.INSTANCE.renderWorld(ctx);
        selectionController.renderPointSelectHighlights(ctx);
        renderAnchor(ctx);
        renderHostFace(ctx);
        renderOrientationMarkers(ctx);
    }

    private void renderAnchor(ToolWorldRenderContext ctx) {
        BlockPos anchor = ComponentTool.INSTANCE.getState().captureDraft.anchor.worldPos;
        if (anchor == null) {
            return;
        }
        renderBlockHighlight(ctx, anchor, 1.0f, 0.55f, 0.1f, 0.85f);
    }

    private void renderHostFace(ToolWorldRenderContext ctx) {
        var draft = ComponentTool.INSTANCE.getState().captureDraft;
        if (draft.host.referenceBlock == null || draft.host.normal == null) {
            return;
        }

        double x0 = draft.host.referenceBlock.getX();
        double y0 = draft.host.referenceBlock.getY();
        double z0 = draft.host.referenceBlock.getZ();
        double x1 = x0 + 1;
        double y1 = y0 + 1;
        double z1 = z0 + 1;
        double t = 0.02;

        Box world = switch (draft.host.normal) {
            case NORTH -> new Box(x0, y0, z0 - t, x1, y1, z0 + t);
            case SOUTH -> new Box(x0, y0, z1 - t, x1, y1, z1 + t);
            case WEST -> new Box(x0 - t, y0, z0, x0 + t, y1, z1);
            case EAST -> new Box(x1 - t, y0, z0, x1 + t, y1, z1);
            case UP -> new Box(x0, y1 - t, z0, x1, y1 + t, z1);
            case DOWN -> new Box(x0, y0 - t, z0, x1, y0 + t, z1);
        };

        Box box = world.offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ).expand(0.001);
        VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, 0.25f, 0.7f, 1.0f, 0.7f);

        double cx = x0 + 0.5 + draft.host.normal.getOffsetX() * 0.5;
        double cy = y0 + 0.5 + draft.host.normal.getOffsetY() * 0.5;
        double cz = z0 + 0.5 + draft.host.normal.getOffsetZ() * 0.5;
        double ex = cx + draft.host.normal.getOffsetX() * 0.6;
        double ey = cy + draft.host.normal.getOffsetY() * 0.6;
        double ez = cz + draft.host.normal.getOffsetZ() * 0.6;
        ToolRenderUtil.line(ctx, cx, cy, cz, ex, ey, ez, 80, 200, 255, 220);
    }

    private void renderOrientationMarkers(ToolWorldRenderContext ctx) {
        var draft = ComponentTool.INSTANCE.getState().captureDraft;

        if (draft.orientation.insideMarkWorld != null) {
            renderBlockHighlight(ctx, draft.orientation.insideMarkWorld, 0.2f, 0.5f, 1.0f, 0.6f);
        }
        if (draft.orientation.outsideMarkWorld != null) {
            renderBlockHighlight(ctx, draft.orientation.outsideMarkWorld, 1.0f, 0.5f, 0.0f, 0.6f);
        }
        if (draft.orientation.bottomMarkWorld != null) {
            renderBlockHighlight(ctx, draft.orientation.bottomMarkWorld, 0.0f, 1.0f, 0.3f, 0.6f);
        }
        if (draft.orientation.topMarkWorld != null) {
            renderBlockHighlight(ctx, draft.orientation.topMarkWorld, 0.8f, 0.2f, 1.0f, 0.6f);
        }

        if (draft.orientation.insideMarkWorld != null && draft.orientation.outsideMarkWorld != null) {
            renderDirectionLine(ctx, draft.orientation.insideMarkWorld, draft.orientation.outsideMarkWorld, 255, 170, 0, 220);
        }
        if (draft.orientation.bottomMarkWorld != null && draft.orientation.topMarkWorld != null) {
            renderDirectionLine(ctx, draft.orientation.bottomMarkWorld, draft.orientation.topMarkWorld, 120, 220, 120, 220);
        }
    }

    private static void renderDirectionLine(
            ToolWorldRenderContext ctx,
            BlockPos from,
            BlockPos to,
            int r, int g, int b, int a
    ) {
        if (from == null || to == null) {
            return;
        }
        double x1 = from.getX() + 0.5;
        double y1 = from.getY() + 0.5;
        double z1 = from.getZ() + 0.5;
        double x2 = to.getX() + 0.5;
        double y2 = to.getY() + 0.5;
        double z2 = to.getZ() + 0.5;
        ToolRenderUtil.line(ctx, x1, y1, z1, x2, y2, z2, r, g, b, a);
    }

    private static void renderBlockHighlight(
            ToolWorldRenderContext ctx,
            BlockPos pos,
            float r, float g, float b, float a
    ) {
        Box worldBox = new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1)
                .expand(0.01);
        Box box = worldBox.offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);
        VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, r, g, b, a);
    }
}
