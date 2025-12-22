package com.formacraft.client.interaction;

import com.formacraft.client.tool.ToolTextRenderUtil;
import com.formacraft.client.tool.ToolWorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * 锚点可视化：青色小立方体 + 悬浮文字。
 */
public final class AnchorRenderer {
    private AnchorRenderer() {}

    public static void render(ToolWorldRenderContext ctx) {
        if (ctx == null) return;
        BlockPos a = AnchorState.get();
        if (a == null) return;

        // 小盒子（略微缩小，避免和方块边重叠）
        Box world = new Box(
                a.getX() + 0.15, a.getY() + 0.15, a.getZ() + 0.15,
                a.getX() + 0.85, a.getY() + 0.85, a.getZ() + 0.85
        );
        Box box = world.offset(-ctx.cameraX, -ctx.cameraY, -ctx.cameraZ);
        VertexRendering.drawBox(ctx.matrices.peek(), ctx.vertexConsumer, box, 0.25f, 0.95f, 0.95f, 0.95f);

        // 悬浮文字（仅在 immediate 可用时）
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        Vec3d pos = new Vec3d(a.getX() + 0.5, a.getY() + 1.2, a.getZ() + 0.5);
        ToolTextRenderUtil.drawBillboardText(
                ctx,
                pos,
                Text.literal("Anchor (" + a.getX() + "," + a.getY() + "," + a.getZ() + ") facing=" + AnchorState.getFacing().name()),
                0xFF40FFFF,
                0.02f
        );
    }
}


