package com.formacraft.client.ui.widget;

import com.formacraft.client.ui.FormaCraftHudOverlay;
import com.formacraft.client.ui.panel.PanelType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 标签栏组件（Minecraft 原生风格）
 * 支持图标、悬停提示和激活状态
 */
public class TabBar {
    private static final int TAB_HEIGHT = 20;
    private static final int TAB_PADDING = 2;
    private static final int ICON_SIZE = 12;
    
    private final List<TabInfo> tabs = new ArrayList<>();
    private final MinecraftClient client;
    private int x;
    private int y;
    
    /**
     * 标签信息
     */
    public static class TabInfo {
        public final PanelType type;
        public final String icon; // Unicode 字符或文本图标
        public final Text tooltip; // 悬停提示
        public final int width; // 标签宽度
        
        public TabInfo(PanelType type, String icon, Text tooltip, int width) {
            this.type = type;
            this.icon = icon;
            this.tooltip = tooltip;
            this.width = width;
        }
    }
    
    public TabBar(MinecraftClient client) {
        this.client = client;
        
        // 初始化标签
        tabs.add(new TabInfo(PanelType.CHAT, "💬", Text.translatable("formacraft.tab.chat"), 20));
        tabs.add(new TabInfo(PanelType.BLUEPRINT, "📋", Text.translatable("formacraft.tab.blueprint"), 20));
        tabs.add(new TabInfo(PanelType.HISTORY, "📜", Text.translatable("formacraft.tab.history"), 20));
        tabs.add(new TabInfo(PanelType.SETTINGS, "⚙", Text.translatable("formacraft.tab.settings"), 20));
    }
    
    /**
     * 设置标签栏位置和宽度
     */
    public void setBounds(int x, int y, int width) {
        this.x = x;
        this.y = y;
        // width 参数保留用于未来扩展（如限制标签栏最大宽度）
    }
    
    /**
     * 渲染标签栏
     */
    public void render(DrawContext ctx, double mouseX, double mouseY) {
        int currentX = x + TAB_PADDING;
        
        for (TabInfo tab : tabs) {
            boolean isActive = FormaCraftHudOverlay.activePanel == tab.type;
            boolean isHovered = isMouseOverTab(currentX, tab, mouseX, mouseY);
            
            // 绘制标签
            drawTab(ctx, currentX, y + TAB_PADDING, tab.width, tab, isActive, isHovered);
            
            currentX += tab.width + TAB_PADDING;
        }
    }
    
    /**
     * 绘制单个标签
     */
    private void drawTab(DrawContext ctx, int x, int y, int width, TabInfo tab, boolean isActive, boolean isHovered) {
        int height = TAB_HEIGHT - TAB_PADDING * 2;
        
        // 背景颜色：激活 > 悬停 > 普通（使用渐变，更符合Minecraft风格）
        int topColor, bottomColor;
        if (isActive) {
            topColor = 0xFF5A5A5A; // 激活状态：较亮的灰色
            bottomColor = 0xFF4A4A4A;
        } else if (isHovered) {
            topColor = 0xFF4A4A4A; // 悬停状态：中等灰色
            bottomColor = 0xFF3A3A3A;
        } else {
            topColor = 0xFF3A3A3A; // 普通状态：深灰色
            bottomColor = 0xFF2A2A2A;
        }
        
        // 绘制渐变背景
        ctx.fillGradient(x, y, x + width, y + height, topColor, bottomColor);
        
        // 绘制边框（Minecraft 原生风格）
        int lightBorder = isActive ? 0xFFAAAAAA : 0xFF666666;
        int darkBorder = 0xFF1A1A1A;
        
        // 上边框和左边框（亮）
        ctx.fill(x, y, x + width, y + 1, lightBorder);
        ctx.fill(x, y, x + 1, y + height, lightBorder);
        
        // 下边框和右边框（暗）
        ctx.fill(x, y + height - 1, x + width, y + height, darkBorder);
        ctx.fill(x + width - 1, y, x + width, y + height, darkBorder);
        
        // 激活状态：底部高亮条
        if (isActive) {
            ctx.fill(x, y + height - 2, x + width, y + height - 1, 0xFFFFD700); // 金色高亮
        }
        
        // 绘制图标（居中）
        int iconX = x + (width - ICON_SIZE) / 2;
        int iconY = y + (height - client.textRenderer.fontHeight) / 2;
        
        int iconColor = isActive ? 0xFFFFFFFF : (isHovered ? 0xFFCCCCCC : 0xFFAAAAAA);
        ctx.drawText(client.textRenderer, tab.icon, iconX, iconY, iconColor, false);
    }
    
    /**
     * 检查鼠标是否在标签上
     */
    private boolean isMouseOverTab(int tabX, TabInfo tab, double mouseX, double mouseY) {
        int tabY = y + TAB_PADDING;
        int tabHeight = TAB_HEIGHT - TAB_PADDING * 2;
        return mouseX >= tabX && mouseX <= tabX + tab.width &&
               mouseY >= tabY && mouseY <= tabY + tabHeight;
    }
    
    /**
     * 处理鼠标点击
     * @return 点击的标签类型，如果未点击任何标签则返回 null
     */
    @Nullable
    public PanelType handleMouseClick(double mouseX, double mouseY, int button) {
        if (button != 0) return null;
        
        int currentX = x + TAB_PADDING;
        
        for (TabInfo tab : tabs) {
            if (isMouseOverTab(currentX, tab, mouseX, mouseY)) {
                return tab.type;
            }
            currentX += tab.width + TAB_PADDING;
        }
        
        return null;
    }
    
    /**
     * 获取悬停标签的提示文本
     */
    @Nullable
    public Text getHoveredTooltip(double mouseX, double mouseY) {
        int currentX = x + TAB_PADDING;
        
        for (TabInfo tab : tabs) {
            if (isMouseOverTab(currentX, tab, mouseX, mouseY)) {
                return tab.tooltip;
            }
            currentX += tab.width + TAB_PADDING;
        }
        
        return null;
    }
    
    /**
     * 获取标签栏高度
     */
    public int getHeight() {
        return TAB_HEIGHT;
    }
}

