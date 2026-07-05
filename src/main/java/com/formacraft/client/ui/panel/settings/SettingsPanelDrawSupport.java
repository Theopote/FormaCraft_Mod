package com.formacraft.client.ui.panel.settings;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.COLOR_GRAY;
import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.COLOR_TOAST_ERROR;
import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.COLOR_TOAST_SUCCESS;
import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.INPUT_HEIGHT;
import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.SECTION_DIVIDER_PAD;

/** Shared draw helpers for {@link com.formacraft.client.ui.panel.SettingsPanel}. */
public final class SettingsPanelDrawSupport {
    private SettingsPanelDrawSupport() {}

    public static void drawSmallLabel(MinecraftClient client, DrawContext ctx, Text label, int x, int y) {
        int labelY = y + (INPUT_HEIGHT - client.textRenderer.fontHeight) / 2;
        ctx.drawTextWithShadow(client.textRenderer, label, x, labelY, COLOR_GRAY);
    }

    /**
     * 绘制分组标题 + 水平分割线，返回分割线之后的 Y（供下一 section 使用）。
     */
    public static int drawSectionHeader(MinecraftClient client, DrawContext ctx, Text title, int x, int y, int w) {
        y += SECTION_DIVIDER_PAD;
        ctx.drawTextWithShadow(client.textRenderer, title, x, y, COLOR_GRAY);
        y += client.textRenderer.fontHeight + 2;
        int lineY = y;
        ctx.fill(x, lineY, x + w, lineY + 1, 0x55AAAAAA);
        return lineY + 1 + SECTION_DIVIDER_PAD;
    }

    public static void drawToast(
            MinecraftClient client,
            DrawContext ctx,
            String toast,
            long toastUntilMs,
            boolean isToastError,
            int x,
            int y
    ) {
        if (toast == null) return;
        long now = System.currentTimeMillis();
        if (now > toastUntilMs) return;

        long remaining = toastUntilMs - now;
        int alpha = 255;
        if (remaining < 500) {
            alpha = (int) (255 * remaining / 500.0);
        }

        int color = isToastError ? COLOR_TOAST_ERROR : COLOR_TOAST_SUCCESS;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int finalColor = (alpha << 24) | (r << 16) | (g << 8) | b;
        ctx.drawTextWithShadow(client.textRenderer, Text.literal(toast), x, y, finalColor);
    }

    public static double scaledMouseX(MinecraftClient client) {
        return client.mouse.getX() / client.getWindow().getScaleFactor();
    }

    public static double scaledMouseY(MinecraftClient client) {
        return client.mouse.getY() / client.getWindow().getScaleFactor();
    }
}
