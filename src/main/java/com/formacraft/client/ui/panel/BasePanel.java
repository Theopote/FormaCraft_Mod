package com.formacraft.client.ui.panel;

import com.formacraft.client.ui.FormacraftUIState;
import com.formacraft.client.ui.FormaCraftHudOverlay;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * 面板基类
 * 所有面板都应该继承此类
 * 固定左侧栏模式：320px 宽度，包含集成工具栏
 */
public abstract class BasePanel {
    protected final MinecraftClient client = MinecraftClient.getInstance();
    
    // 固定 UI 区域（左侧栏）
    protected int panelX = 0;
    protected int panelY = 0;
    protected int panelWidth = 320;     // 左侧栏宽度固定
    protected int panelHeight = 0;
    protected boolean visible = true;

    // 工具栏高度
    private static final int TOOLBAR_HEIGHT = 22;
    private static final int TOOLBAR_PADDING = 8;
    
    // 关闭按钮区域
    private static final int CLOSE_BUTTON_SIZE = 12;
    private static final int CLOSE_BUTTON_PADDING = 4;

    /**
     * 渲染面板（主入口）
     */
    public void render(DrawContext ctx) {
        if (!visible) return;
        
        // 更新面板高度为窗口高度
        this.panelHeight = client.getWindow().getScaledHeight();
        
        // 绘制背景
        drawBackground(ctx);
        
        // 绘制工具栏
        drawToolbar(ctx);
        
        // 绘制面板内容
        drawContents(ctx);
    }

    /**
     * 绘制半透明背景
     */
    protected void drawBackground(DrawContext ctx) {
        int h = panelHeight;
        ctx.fill(panelX, panelY, panelX + panelWidth, panelY + h, 0x88000000);
    }

    /**
     * 绘制顶部工具栏
     */
    protected void drawToolbar(DrawContext ctx) {
        int bx = panelX;
        int by = panelY;
        int bw = panelWidth;
        int bh = TOOLBAR_HEIGHT;

        // 顶部工具栏背景
        ctx.fill(bx, by, bx + bw, by + bh, 0xAA222222);

        int x = bx + TOOLBAR_PADDING;
        drawTab(ctx, "Chat", PanelType.CHAT, x, by + 6);
        x += 60;
        drawTab(ctx, "Blueprint", PanelType.BLUEPRINT, x, by + 6);
        x += 80;
        drawTab(ctx, "Settings", PanelType.SETTINGS, x, by + 6);
        x += 70;
        drawTab(ctx, "History", PanelType.HISTORY, x, by + 6);
        
        // 绘制关闭按钮（右上角）
        drawCloseButton(ctx);
    }
    
    /**
     * 绘制关闭按钮
     */
    private void drawCloseButton(DrawContext ctx) {
        int closeX = panelX + panelWidth - CLOSE_BUTTON_SIZE - CLOSE_BUTTON_PADDING;
        int closeY = panelY + CLOSE_BUTTON_PADDING;
        int closeW = CLOSE_BUTTON_SIZE;
        int closeH = CLOSE_BUTTON_SIZE;
        
        // 关闭按钮背景（红色）
        ctx.fill(closeX, closeY, closeX + closeW, closeY + closeH, 0xFFAA4444);
        
        // 绘制 "X" 文字
        ctx.drawCenteredTextWithShadow(client.textRenderer, "X", 
                closeX + closeW / 2, closeY + 2, 0xFFFFFF);
    }
    
    /**
     * 检查鼠标是否在关闭按钮内
     */
    private boolean isMouseOverCloseButton(double mouseX, double mouseY) {
        int closeX = panelX + panelWidth - CLOSE_BUTTON_SIZE - CLOSE_BUTTON_PADDING;
        int closeY = panelY + CLOSE_BUTTON_PADDING;
        int closeW = CLOSE_BUTTON_SIZE;
        int closeH = CLOSE_BUTTON_SIZE;
        
        return mouseX >= closeX && mouseX <= closeX + closeW &&
               mouseY >= closeY && mouseY <= closeY + closeH;
    }

    /**
     * 绘制工具栏标签
     */
    private void drawTab(DrawContext ctx, String text, PanelType type, int x, int y) {
        int color = (FormaCraftHudOverlay.activePanel == type) ? 0xFFFFFF : 0xAAAAAA;
        ctx.drawText(client.textRenderer, text, x, y, color, false);
    }

    /**
     * 绘制面板内容（子类实现）
     */
    protected abstract void drawContents(DrawContext ctx);

    /**
     * 处理鼠标点击
     * @return 是否处理了点击事件
     */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        
        // 检查鼠标是否在面板内
        if (!isMouseOver(mouseX, mouseY)) return false;
        
        // 检查是否点击了关闭按钮
        if (isMouseOverCloseButton(mouseX, mouseY)) {
            FormacraftUIState.close();
            return true;
        }
        
        // 顶部 tab 区域
        int by = panelY;
        int bh = TOOLBAR_HEIGHT;
        if (mouseY >= by && mouseY <= by + bh) {
            // 简单 hit-test，计算点击的是哪一块
            int x = panelX + TOOLBAR_PADDING;
            if (mouseX >= x && mouseX <= x + 50) {
                FormaCraftHudOverlay.activePanel = PanelType.CHAT;
                return true;
            }
            x += 60;
            if (mouseX >= x && mouseX <= x + 70) {
                FormaCraftHudOverlay.activePanel = PanelType.BLUEPRINT;
                return true;
            }
            x += 80;
            if (mouseX >= x && mouseX <= x + 70) {
                FormaCraftHudOverlay.activePanel = PanelType.SETTINGS;
                return true;
            }
            x += 70;
            if (mouseX >= x && mouseX <= x + 60) {
                FormaCraftHudOverlay.activePanel = PanelType.HISTORY;
                return true;
            }
        }
        
        return false;
    }

    /**
     * 处理键盘输入（子类可重写）
     */
    public void keyPressed(int keyCode) {
        // 默认实现，保持向后兼容
    }

    /**
     * 处理键盘输入（带修饰符，子类可重写）
     */
    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        // 默认调用简化版本以保持向后兼容
        keyPressed(keyCode);
    }

    /**
     * 处理字符输入（子类可重写）
     */
    public void charTyped(char chr) {}

    /**
     * 处理鼠标滚轮（子类可重写）
     * @param mouseX 鼠标 X 坐标
     * @param mouseY 鼠标 Y 坐标
     * @param amount 滚动量（正数向上，负数向下）
     */
    public void mouseScrolled(double mouseX, double mouseY, double amount) {}

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public boolean isVisible() {
        return visible;
    }

    /**
     * 检查鼠标是否在面板内
     */
    public boolean isMouseOver(double mouseX, double mouseY) {
        return mouseX >= panelX && mouseX <= panelX + panelWidth && 
               mouseY >= panelY && mouseY <= panelY + panelHeight;
    }

    public int getPanelX() { return panelX; }
    public int getPanelY() { return panelY; }
    public int getPanelWidth() { return panelWidth; }
    public int getPanelHeight() { return panelHeight; }
    public int getContentY() { return panelY + TOOLBAR_HEIGHT; }
    public int getContentHeight() { return panelHeight - TOOLBAR_HEIGHT; }
}
