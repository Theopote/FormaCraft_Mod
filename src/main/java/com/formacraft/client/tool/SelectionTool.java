package com.formacraft.client.tool;

import com.formacraft.client.interaction.CursorRaycastHelper;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * 两点框选工具：左键第一次设置起点，第二次设置终点。
 */
public final class SelectionTool implements FormacraftTool {
    public static final SelectionTool INSTANCE = new SelectionTool();

    private SelectionTool() {}

    private BlockPos start;
    private BlockPos end;
    private boolean selecting = false;

    @Override
    public String getId() {
        return "selection";
    }

    @Override
    public Text getDisplayName() {
        return Text.literal("选区工具");
    }

    @Override
    public boolean onMouseClick(double mx, double my, int button) {
        if (button != 0) return false;

        BlockHitResult hit = CursorRaycastHelper.getLastBlockHit();
        if (hit == null) return true; // 工具吃掉点击，但没有命中方块

        BlockPos pos = hit.getBlockPos();
        if (!selecting) {
            start = pos;
            end = pos;
            selecting = true;
        } else {
            end = pos;
            selecting = false;
        }
        return true;
    }

    @Override
    public void tick() {
        if (!selecting) return;

        BlockHitResult hit = CursorRaycastHelper.getLastBlockHit();
        if (hit != null) {
            end = hit.getBlockPos();
        }
    }

    @Override
    public void renderWorld(ToolWorldRenderContext ctx) {
        BlockPos min = getMin();
        BlockPos max = getMax();
        if (min == null || max == null) return;

        Box worldBox = new Box(
                min.getX(), min.getY(), min.getZ(),
                max.getX() + 1, max.getY() + 1, max.getZ() + 1
        ).expand(0.01);

        Box box = worldBox.offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);
        VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, 0.35f, 0.85f, 1.00f, 0.65f);

        double s = 0.12;
        drawCorner(ctx, min.getX(), min.getY(), min.getZ(), s);
        drawCorner(ctx, max.getX() + 1, min.getY(), min.getZ(), s);
        drawCorner(ctx, min.getX(), max.getY() + 1, min.getZ(), s);
        drawCorner(ctx, max.getX() + 1, max.getY() + 1, min.getZ(), s);
        drawCorner(ctx, min.getX(), min.getY(), max.getZ() + 1, s);
        drawCorner(ctx, max.getX() + 1, min.getY(), max.getZ() + 1, s);
        drawCorner(ctx, min.getX(), max.getY() + 1, max.getZ() + 1, s);
        drawCorner(ctx, max.getX() + 1, max.getY() + 1, max.getZ() + 1, s);
    }

    private void drawCorner(ToolWorldRenderContext ctx, double wx, double wy, double wz, double size) {
        Box corner = new Box(
                wx - size, wy - size, wz - size,
                wx + size, wy + size, wz + size
        ).offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);
        VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, corner, 0.85f, 0.95f, 1.00f, 0.95f);
    }

    public boolean hasSelection() {
        return start != null && end != null && !selecting;
    }

    public boolean isSelecting() {
        return selecting;
    }

    public BlockPos getMin() {
        if (start == null || end == null) return null;
        return new BlockPos(
                Math.min(start.getX(), end.getX()),
                Math.min(start.getY(), end.getY()),
                Math.min(start.getZ(), end.getZ())
        );
    }

    public BlockPos getMax() {
        if (start == null || end == null) return null;
        return new BlockPos(
                Math.max(start.getX(), end.getX()),
                Math.max(start.getY(), end.getY()),
                Math.max(start.getZ(), end.getZ())
        );
    }

    public void clear() {
        this.start = null;
        this.end = null;
        this.selecting = false;
    }
}

