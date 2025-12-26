package com.formacraft.client.preview;

import com.formacraft.client.tool.ToolWorldRenderContext;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.util.math.Box;

import java.util.List;

/**
 * SkeletonPreviewRenderer:
 * Draws J-layer skeleton layout preview (circles/rectangles/paths) as lightweight wireframes.
 */
public final class SkeletonPreviewRenderer {
    private SkeletonPreviewRenderer() {}

    private static final int MAX_VISUALS = 2000;

    public static void render(ToolWorldRenderContext ctx) {
        if (!SkeletonPreviewState.isActive()) return;
        List<SkeletonPreviewState.Visual> vs = SkeletonPreviewState.getVisuals();
        if (vs == null || vs.isEmpty()) return;

        int n = vs.size();
        int step = Math.max(1, (int) Math.ceil(n / (double) MAX_VISUALS));
        for (int i = 0; i < n; i += step) {
            SkeletonPreviewState.Visual v = vs.get(i);
            if (v == null || v.box == null) continue;
            Box box = v.box.offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);
            VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, v.r, v.g, v.b, v.a);
        }
    }
}


