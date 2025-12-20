package com.formacraft.client.preview;

import com.formacraft.client.tool.ToolWorldRenderContext;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.List;

/**
 * Patch 预览渲染：用不同颜色区分 place/remove/replace。
 * <p>
 * 不依赖 WorldRenderEvents，由我们的 Mixin 渲染注入点驱动。
 */
public final class PatchPreviewRenderer {
    private PatchPreviewRenderer() {}

    private static final int MAX_BOXES = 2500;

    public static void render(ToolWorldRenderContext ctx) {
        if (!PatchPreviewState.isEnabled()) return;

        BlockPos origin = PatchPreviewState.getOrigin();
        List<BlockPatch> patches = PatchPreviewState.getPatches();
        if (origin == null || patches == null || patches.isEmpty()) return;

        int n = patches.size();
        int step = Math.max(1, (int) Math.ceil(n / (double) MAX_BOXES));
        for (int i = 0; i < n; i += step) {
            BlockPatch p = patches.get(i);
            if (p == null) continue;

            BlockPos pos = origin.add(p.dx(), p.dy(), p.dz());
            Box world = new Box(pos).expand(0.02);
            Box box = world.offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);

            float r = 1f, g = 1f, b = 1f, a = 0.90f;
            String action = p.action() == null ? "" : p.action().toLowerCase();
            switch (action) {
                case BlockPatch.PLACE -> { r = 0.25f; g = 1.00f; b = 0.25f; }   // green
                case BlockPatch.REMOVE -> { r = 1.00f; g = 0.25f; b = 0.25f; }  // red
                case BlockPatch.REPLACE -> { r = 1.00f; g = 0.85f; b = 0.25f; } // yellow
                default -> { /* keep white */ }
            }

            VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, r, g, b, a);
        }
    }
}

