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
    // 统一控件高度：16（与工具栏按钮一致）
    private static final int TAB_HEIGHT = 16;
    // 标签之间的间距（不要占用高度）
    private static final int TAB_GAP = 2;
    // 标签内部不再额外 padding，确保按钮实际高度=16
    private static final int TAB_PADDING = 0;
    private static final int TAB_SIZE = TAB_HEIGHT;
    
    private final List<TabInfo> tabs = new ArrayList<>();
    private final MinecraftClient client;
    private int x;
    private int y;
    private int maxWidth = Integer.MAX_VALUE;
    
    /**
     * 标签信息
     */
    public static class TabInfo {
        public final PanelType type;
        public final String icon; // MC 位图字体可显示的 ASCII 图标
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
        
        // ASCII 图标：避免 emoji 在 MC 默认字体下显示为方块
        tabs.add(new TabInfo(PanelType.CHAT, "C", Text.translatable("formacraft.tab.chat"), TAB_SIZE));
        tabs.add(new TabInfo(PanelType.TOOLS, "T", Text.translatable("formacraft.tab.tools"), TAB_SIZE));
        tabs.add(new TabInfo(PanelType.COMPONENT_LIBRARY, "L", Text.translatable("formacraft.tab.component_library"), TAB_SIZE));
        tabs.add(new TabInfo(PanelType.COMPONENT_CAPTURE, "P", Text.translatable("formacraft.tab.component_capture"), TAB_SIZE));
        tabs.add(new TabInfo(PanelType.SETTINGS, "S", Text.translatable("formacraft.tab.settings"), TAB_SIZE));
    }
    
    /**
     * 设置标签栏位置和可用宽度（超出宽度的尾部 Tab 不绘制、不可点击）
     */
    public void setBounds(int x, int y, int width) {
        this.x = x;
        this.y = y;
        this.maxWidth = Math.max(0, width);
    }

    private boolean tabFits(int tabX, TabInfo tab) {
        return tabX + tab.width <= x + maxWidth;
    }
    
    /**
     * 渲染标签栏
     */
    public void render(DrawContext ctx, double mouseX, double mouseY) {
        int currentX = x + TAB_PADDING;
        
        for (TabInfo tab : tabs) {
            if (!tabFits(currentX, tab)) {
                break;
            }
            boolean isActive = FormaCraftHudOverlay.activePanel == tab.type;
            boolean isHovered = isMouseOverTab(currentX, tab, mouseX, mouseY);
            
            drawTab(ctx, currentX, y + TAB_PADDING, tab.width, tab, isActive, isHovered);
            
            currentX += tab.width + TAB_GAP;
        }
    }
    
    /**
     * 绘制单个标签（使用 Minecraft 原生按钮纹理）
     */
    private void drawTab(DrawContext ctx, int x, int y, int width, TabInfo tab, boolean isActive, boolean isHovered) {
        int height = TAB_HEIGHT;

        int topColor, bottomColor;
        if (isActive) {
            topColor = 0xFF4B4B4B;
            bottomColor = 0xFF3B3B3B;
        } else if (isHovered) {
            topColor = 0xFF6B6B6B;
            bottomColor = 0xFF4B4B4B;
        } else {
            topColor = 0xFF5B5B5B;
            bottomColor = 0xFF3B3B3B;
        }
        ctx.fillGradient(x, y, x + width, y + height, topColor, bottomColor);

        int lightBorder, darkBorder;
        if (isActive) {
            lightBorder = 0xFF000000;
            darkBorder = isHovered ? 0xFFCCCCCC : 0xFFAAAAAA;
        } else {
            lightBorder = isHovered ? 0xFFCCCCCC : 0xFFAAAAAA;
            darkBorder = 0xFF000000;
        }
        
        ctx.fill(x, y, x + width, y + 1, lightBorder);
        ctx.fill(x, y, x + 1, y + height, lightBorder);
        ctx.fill(x, y + height - 1, x + width, y + height, darkBorder);
        ctx.fill(x + width - 1, y, x + width, y + height, darkBorder);

        int centerX = x + width / 2;
        int centerY = y + height / 2;
        int iconY = centerY - client.textRenderer.fontHeight / 2;
        ctx.drawCenteredTextWithShadow(client.textRenderer, Text.literal(tab.icon), centerX, iconY, 0xFFFFFFFF);
    }
    
    private boolean isMouseOverTab(int tabX, TabInfo tab, double mouseX, double mouseY) {
        int tabY = y + TAB_PADDING;
        return mouseX >= tabX && mouseX <= tabX + tab.width &&
               mouseY >= tabY && mouseY <= tabY + TAB_HEIGHT;
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
            if (!tabFits(currentX, tab)) {
                break;
            }
            if (isMouseOverTab(currentX, tab, mouseX, mouseY)) {
                return tab.type;
            }
            currentX += tab.width + TAB_GAP;
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
            if (!tabFits(currentX, tab)) {
                break;
            }
            if (isMouseOverTab(currentX, tab, mouseX, mouseY)) {
                return tab.tooltip;
            }
            currentX += tab.width + TAB_GAP;
        }
        
        return null;
    }
    
    public int getHeight() {
        return TAB_HEIGHT;
    }
}
