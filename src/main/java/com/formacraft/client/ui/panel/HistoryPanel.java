package com.formacraft.client.ui.panel;

import net.minecraft.client.gui.DrawContext;

/**
 * 对话历史面板
 * 固定左侧栏模式：320px 宽度，包含集成工具栏
 */
public class HistoryPanel extends BasePanel {

    @Override
    protected void drawContents(DrawContext ctx) {
        int innerX = panelX + 8;
        int innerY = panelY + 30;
        ctx.drawText(client.textRenderer, "History (TODO)", innerX, innerY, 0xFFFFFF, false);
    }
}
