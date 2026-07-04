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
 * - 优先使用服务端下发的 {@link OutlinePreviewState#blocks}（真实占用）
 * - 同时绘制整体包围盒，保证远距离也能看清范围
 */
public final class BuildingOutlineRenderer {
    private BuildingOutlineRenderer() {}

    private static final int MAX_BOXES = 2000; // 避免渲染过重（城市/复合结构时）

    public static void render(ToolWorldRenderContext ctx) {
        // OutlinePreviewState 是“是否有轮廓可画”的权威开关（setBlocks 置真，clear/hide 清空）。
        // 不再额外要求 BuildingPreviewState.isActive()——那需要 BuildingSpec，会导致
        // LlmPlan / Composite / City 等无 spec 的预览无法在世界中显示轮廓。
        if (!OutlinePreviewState.active) return;

        List<OutlineBlock> blocks = OutlinePreviewState.blocks;
        if (blocks == null || blocks.isEmpty()) return;

        // 预览色：青绿色
        float r = 0.20f;
        float g = 0.90f;
        float b = 1.00f;
        float a = 0.95f;

        // 计算整体包围盒（用于远观）
        BlockPos min = null;
        BlockPos max = null;
        for (OutlineBlock ob : blocks) {
            if (ob == null || ob.pos == null) continue;
            BlockPos p = ob.pos;
            if (min == null) {
                min = p;
                max = p;
            } else {
                min = new BlockPos(
                        Math.min(min.getX(), p.getX()),
                        Math.min(min.getY(), p.getY()),
                        Math.min(min.getZ(), p.getZ())
                );
                max = new BlockPos(
                        Math.max(max.getX(), p.getX()),
                        Math.max(max.getY(), p.getY()),
                        Math.max(max.getZ(), p.getZ())
                );
            }
        }

        if (min != null) {
            Box world = new Box(
                    min.getX(), min.getY(), min.getZ(),
                    max.getX() + 1, max.getY() + 1, max.getZ() + 1
            ).expand(0.02);
            Box box = world.offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);
            VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, r, g, b, 0.35f);
        }

        // 单方块轮廓（真实占用）
        // 优先画“合并后的外形盒子”，更像整体形态；fallback 才画单方块
        if (OutlinePreviewState.mergedBoxes != null && !OutlinePreviewState.mergedBoxes.isEmpty()) {
            List<Box> merged = OutlinePreviewState.mergedBoxes;
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

