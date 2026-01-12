package com.formacraft.client.ui.toast;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * HUD 简易 Toast（非原版 ToastManager）。
 * <p>
 * 设计目标：
 * - 轻量：不依赖 Screen
 * - 通用：ToolPanel / 其它 HUD 面板都可复用
 */
public final class HudToast {
    private HudToast() {}

    private static final long DURATION_MS = 2500L;

    private static volatile String toast = null;
    private static volatile long toastUntilMs = 0L;
    private static volatile boolean toastError = false;

    public static void show(String msg, boolean isError) {
        String t = (msg == null) ? "" : msg.trim();
        if (t.isEmpty()) return;
        toast = t;
        toastError = isError;
        toastUntilMs = System.currentTimeMillis() + DURATION_MS;
    }

    public static void show(String msg) {
        show(msg, false);
    }

    public static void render(DrawContext ctx, int x, int y) {
        if (toast == null) return;
        long now = System.currentTimeMillis();
        if (now > toastUntilMs) {
            toast = null;
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        TextRenderer tr = client.textRenderer;
        if (tr == null) return;

        long remaining = toastUntilMs - now;
        int alpha = 255;
        if (remaining < 500) {
            alpha = (int) (255 * remaining / 500.0);
        }

        int base = toastError ? 0xFF8888 : 0x88FF88; // 0xRRGGBB
        int r = (base >> 16) & 0xFF;
        int g = (base >> 8) & 0xFF;
        int b = base & 0xFF;
        int color = (alpha << 24) | (r << 16) | (g << 8) | b;

        ctx.drawTextWithShadow(tr, Text.literal(toast), x, y, color);
    }
}

