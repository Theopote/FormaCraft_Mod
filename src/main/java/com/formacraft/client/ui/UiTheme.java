package com.formacraft.client.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.List;

/**
 * FormaCraft HUD 面板共享视觉常量与辅助方法。
 */
public final class UiTheme {
    private UiTheme() {}

    public static final int SIDEBAR_EXPANDED_WIDTH = 160;
    public static final int SIDEBAR_COLLAPSED_WIDTH = 12;

    public static final int CONTENT_PADDING = 10;
    /** Preserve the original HUD opacity so the world remains visible. */
    public static final int CONTENT_BG = 0x80101010;
    public static final int CHAT_MESSAGE_BG = 0x90181818;
    public static final int CHAT_LINE_HEIGHT = 10;
    public static final int DIVIDER_TOOLBAR = 0xFF7A7A7A;
    public static final int DIVIDER_SECTION = 0xFF7A7A7A;
    public static final int DIVIDER_SUBTLE = 0xFF444444;
    public static final int BORDER = 0xFFB0B0B0;
    public static final int BORDER_SHADOW = 0xFF555555;
    public static final int ACCENT = 0xFFFFFFFF;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int TOOLBAR_BG = 0x80101010;
    public static final int TAB_UNSELECTED_BG = 0xFF8A8A8A;
    public static final int TAB_HOVER_BG = 0xFFB0B0B0;
    public static final int TAB_SELECTED_BG = CONTENT_BG;
    public static final int CONTENT_SOLID_BG = CONTENT_BG;

    public static int contentInnerLeft(int panelX) {
        return panelX + 1;
    }

    public static int contentInnerRight(int panelX, int panelWidth) {
        return panelX + panelWidth - 1;
    }

    public static void drawContentBackground(DrawContext ctx, int panelX, int contentY, int panelWidth, int panelBottom) {
        ctx.fill(contentInnerLeft(panelX), contentY, contentInnerRight(panelX, panelWidth), panelBottom, CONTENT_BG);
    }

    /** Pixel bevel: light on top/left, shadow on bottom/right. */
    public static void drawPanelBorder(DrawContext ctx, int x0, int y0, int x1, int y1) {
        if (x1 <= x0 || y1 <= y0) return;
        ctx.fill(x0, y0, x1, y0 + 1, BORDER);
        ctx.fill(x0, y1 - 1, x1, y1, BORDER_SHADOW);
        ctx.fill(x0, y0, x0 + 1, y1, BORDER);
        ctx.fill(x1 - 1, y0, x1, y1, BORDER_SHADOW);
    }

    /**
     * Content-frame border that rises around the active tab only.
     * Inactive tabs / toolbar buttons stay outside the outline.
     *
     * @param contentTop y of the content-area top edge (shared seam under tabs)
     * @param tabX       active tab left, or {@code Integer.MIN_VALUE} if none
     * @param tabY       active tab top
     * @param tabW       active tab width
     */
    public static void drawTabbedPanelBorder(DrawContext ctx,
                                             int x0, int contentTop, int x1, int y1,
                                             int tabX, int tabY, int tabW) {
        if (x1 <= x0 || y1 <= contentTop) return;

        boolean hasTab = tabW > 0 && tabX > Integer.MIN_VALUE
                && tabX < x1 && tabX + tabW > x0;

        // Content left / right / bottom
        ctx.fill(x0, contentTop, x0 + 1, y1, BORDER);
        ctx.fill(x1 - 1, contentTop, x1, y1, BORDER_SHADOW);
        ctx.fill(x0, y1 - 1, x1, y1, BORDER_SHADOW);

        if (!hasTab) {
            ctx.fill(x0, contentTop, x1, contentTop + 1, BORDER);
            return;
        }

        int tabRight = tabX + tabW;
        // Content top with opening under the active tab
        if (tabX > x0) {
            ctx.fill(x0, contentTop, tabX + 1, contentTop + 1, BORDER);
        }
        if (tabRight < x1) {
            ctx.fill(tabRight - 1, contentTop, x1, contentTop + 1, BORDER);
        }

        // Active tab: top + left + right (same gray as the content frame)
        ctx.fill(tabX, tabY, tabRight, tabY + 1, BORDER);
        ctx.fill(tabX, tabY, tabX + 1, contentTop + 1, BORDER);
        ctx.fill(tabRight - 1, tabY, tabRight, contentTop + 1, BORDER);
    }

    /**
     * HUD 模式手绘 tooltip（Screen 打开时委托给原版 drawTooltip）。
     */
    public static void drawTooltip(DrawContext ctx, MinecraftClient client, List<Text> lines, int mouseX, int mouseY) {
        if (lines == null || lines.isEmpty() || client == null) return;
        if (client.currentScreen != null) {
            ctx.drawTooltip(client.textRenderer, lines, mouseX, mouseY);
            return;
        }

        int padding = 4;
        int lineGap = 2;
        int maxW = 0;
        for (Text t : lines) {
            if (t == null) continue;
            maxW = Math.max(maxW, client.textRenderer.getWidth(t));
        }
        int lineH = client.textRenderer.fontHeight;
        int boxW = maxW + padding * 2;
        int boxH = padding * 2 + lines.size() * lineH + Math.max(0, lines.size() - 1) * lineGap;

        int x = mouseX + 10;
        int y = mouseY + 8;
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();
        if (x + boxW > sw) x = Math.max(0, sw - boxW - 2);
        if (y + boxH > sh) y = Math.max(0, sh - boxH - 2);

        int bg = 0xF0100010;
        int border = 0x500000FF;
        ctx.fill(x, y, x + boxW, y + boxH, bg);
        ctx.fill(x, y, x + boxW, y + 1, border);
        ctx.fill(x, y + boxH - 1, x + boxW, y + boxH, border);
        ctx.fill(x, y, x + 1, y + boxH, border);
        ctx.fill(x + boxW - 1, y, x + boxW, y + boxH, border);

        int ty = y + padding;
        for (Text t : lines) {
            if (t != null) {
                ctx.drawTextWithShadow(client.textRenderer, t, x + padding, ty, 0xFFFFFFFF);
            }
            ty += lineH + lineGap;
        }
    }
}
