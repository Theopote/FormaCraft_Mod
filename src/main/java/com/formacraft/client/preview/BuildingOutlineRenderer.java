package com.formacraft.client.preview;

import com.formacraft.client.tool.ToolWorldRenderContext;
import com.formacraft.common.preview.OutlineBlock;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.List;

/**
 * 世界中绘制“将要占用哪些方块”的预览轮廓。
 * <p>
 * - 优先使用服务端下发的 {@link OutlinePreviewState} 快照（真实占用）
 * - 同时绘制预计算的整体包围盒，保证远距离也能看清范围
 */
public final class BuildingOutlineRenderer {
    private BuildingOutlineRenderer() {}

    private static final int MAX_BOXES = 2000; // 避免渲染过重（城市/复合结构时）

    public static void render(ToolWorldRenderContext ctx) {
        OutlinePreviewState.Snapshot snap = OutlinePreviewState.snapshot();
        if (!snap.active()) return;

        List<OutlineBlock> blocks = snap.blocks();
        if (blocks.isEmpty()) return;

        float r = 0.20f;
        float g = 0.90f;
        float b = 1.00f;
        float a = 0.95f;

        Box overall = snap.overallBounds();
        if (overall != null) {
            Box box = overall.offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);
            VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, r, g, b, 0.35f);
        }

        List<Box> merged = snap.mergedBoxes();
        if (!merged.isEmpty()) {
            int n = merged.size();
            int step = Math.max(1, (int) Math.ceil(n / (double) MAX_BOXES));
            for (int i = 0; i < n; i += step) {
                Box world = merged.get(i);
                if (world == null) continue;
                Box box = world.offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);
                VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, r, g, b, a);
            }
        } else {
            int n = blocks.size();
            int step = Math.max(1, (int) Math.ceil(n / (double) MAX_BOXES));
            for (int i = 0; i < n; i += step) {
                OutlineBlock ob = blocks.get(i);
                if (ob == null || ob.pos == null) continue;
                BlockPos p = ob.pos;
                Box world = new Box(p).expand(0.01);
                Box box = world.offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);
                VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, r, g, b, a);
            }
        }
    }
}
