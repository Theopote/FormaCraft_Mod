package com.formacraft.client.ui.panel.settings;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.COLOR_GRAY;
import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.COLOR_TOAST_ERROR;
import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.COLOR_TOAST_SUCCESS;
import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.INPUT_HEIGHT;

/** Shared draw helpers for {@link com.formacraft.client.ui.panel.SettingsPanel}. */
public final class SettingsPanelDrawSupport {
    private SettingsPanelDrawSupport() {}

    public static void drawSmallLabel(MinecraftClient client, DrawContext ctx, Text label, int x, int y) {
        int labelY = y + (INPUT_HEIGHT - client.textRenderer.fontHeight) / 2;
        ctx.drawTextWithShadow(client.textRenderer, label, x, labelY, COLOR_GRAY);
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
