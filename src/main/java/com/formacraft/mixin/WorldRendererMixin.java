package com.formacraft.mixin;

import com.formacraft.client.ui.FormacraftUIState;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 方块 hover 边框颜色（发光彩虹呼吸）。
 * <p>
 * 原版在 WorldRenderer#drawBlockOutline 里接收一个 int 颜色参数。
 * 这里直接修改该参数，避免依赖内部私有绘制函数名/签名（更稳定）。
 */
@Mixin(WorldRenderer.class)
public class WorldRendererMixin {

    @ModifyVariable(
            method = "drawBlockOutline(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;DDDLnet/minecraft/client/render/state/OutlineRenderState;I)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            require = 0
    )
    private int formacraft$outlineColor(int originalColor) {
        if (!FormacraftUIState.isOpen) return originalColor;

        // 彩虹呼吸 + 更亮（模拟发光感）
        double t = System.currentTimeMillis() / 1000.0;
        float hue = (float) ((t * 0.12) % 1.0);          // 彩虹循环速度
        float breathe = (float) (0.5 + 0.5 * Math.sin(t * 2.0)); // 呼吸（0~1）

        float[] rgb = hsvToRgb(hue);

        // “发光感”：在呼吸高点向白色靠拢
        float glowMix = 0.45f * breathe;
        float r = lerp(rgb[0], glowMix);
        float g = lerp(rgb[1], glowMix);
        float b = lerp(rgb[2], glowMix);

        // alpha 呼吸
        float a = 0.25f + 0.75f * breathe;

        int ri = clamp255(Math.round(r * 255f));
        int gi = clamp255(Math.round(g * 255f));
        int bi = clamp255(Math.round(b * 255f));
        int ai = clamp255(Math.round(a * 255f));

        return (ai << 24) | (ri << 16) | (gi << 8) | bi;
    }

    @Unique
    private static int clamp255(int v) {
        if (v < 0) return 0;
        return Math.min(v, 255);
    }

    @Unique
    private static float lerp(float a, float t) {
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        return a + ((float) 1.0 - a) * t;
    }

    @Unique
    private static float[] hsvToRgb(float h) {
        h = h - (float) Math.floor(h);

        float hh = h * 6f;
        int i = (int) Math.floor(hh);
        float f = hh - i;
        float p = 0.0f;
        float q = (1f - f);
        float t = (1f - (1f - f));

        return switch (i % 6) {
            case 0 -> new float[]{(float) 1.0, t, p};
            case 1 -> new float[]{q, (float) 1.0, p};
            case 2 -> new float[]{p, (float) 1.0, t};
            case 3 -> new float[]{p, q, (float) 1.0};
            case 4 -> new float[]{t, p, (float) 1.0};
            default -> new float[]{(float) 1.0, p, q};
        };
    }
}

