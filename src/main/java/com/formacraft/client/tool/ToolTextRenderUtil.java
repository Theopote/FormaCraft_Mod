package com.formacraft.client.tool;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;

/**
 * 世界悬浮文字渲染（billboard，朝向相机）。
 * <p>注意：依赖 ToolWorldRenderContext.immediate（VertexConsumerProvider.Immediate）。</p>
 */
public final class ToolTextRenderUtil {
    private ToolTextRenderUtil() {}

    /**
     * 在世界坐标绘制居中文字（默认全亮）。
     */
    public static void drawBillboardText(ToolWorldRenderContext ctx, Vec3d worldPos, Text text, int argb, float scale) {
        if (ctx == null || ctx.immediate == null || text == null || worldPos == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.textRenderer == null || mc.gameRenderer == null) return;

        TextRenderer tr = mc.textRenderer;
        int width = tr.getWidth(text);

        // camera rotation
        Quaternionf q = mc.gameRenderer.getCamera().getRotation();

        ctx.matrices.push();
        try {
            // translate to world position (relative to camera)
            ctx.matrices.translate(worldPos.x - ctx.cameraX, worldPos.y - ctx.cameraY, worldPos.z - ctx.cameraZ);
            // face camera
            ctx.matrices.multiply(q);
            // flip & scale (MC convention：负缩放使文字正向)
            float s = scale <= 0 ? 0.02f : scale;
            ctx.matrices.scale(-s, -s, s);

            // center align
            float x = -width / 2.0f;
            float y = 0.0f;

            int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
            // background 透明：0
            tr.draw(text, x, y, argb, false, ctx.matrices.peek().getPositionMatrix(), ctx.immediate,
                    TextRenderer.TextLayerType.NORMAL, 0, light);
        } finally {
            ctx.matrices.pop();
        }
    }
}


