package com.formacraft.client.ui.widget;

import com.formacraft.client.ui.FormaCraftHudOverlay;
import com.formacraft.client.ui.panel.PanelType;
import com.formacraft.client.ui.render.ImageRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
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
        public final BufferedImage icon; // PNG 图标
        public final Text tooltip; // 悬停提示
        public final int width; // 标签宽度

        public TabInfo(PanelType type, BufferedImage icon, Text tooltip, int width) {
            this.type = type;
            this.icon = icon;
            this.tooltip = tooltip;
            this.width = width;
        }
    }
    
    public TabBar(MinecraftClient client) {
        this.client = client;

        // 加载 PNG 图标
        tabs.add(new TabInfo(PanelType.CHAT, loadIcon("textures/gui/tabs/chat.png"), Text.translatable("formacraft.tab.chat"), TAB_SIZE));
        tabs.add(new TabInfo(PanelType.TOOLS, loadIcon("textures/gui/tabs/tools.png"), Text.translatable("formacraft.tab.tools"), TAB_SIZE));
        tabs.add(new TabInfo(PanelType.COMPONENT_LIBRARY, loadIcon("textures/gui/tabs/library.png"), Text.translatable("formacraft.tab.component_library"), TAB_SIZE));
        tabs.add(new TabInfo(PanelType.COMPONENT_CAPTURE, loadIcon("textures/gui/tabs/capture.png"), Text.translatable("formacraft.tab.component_capture"), TAB_SIZE));
        tabs.add(new TabInfo(PanelType.SETTINGS, loadIcon("textures/gui/tabs/settings.png"), Text.translatable("formacraft.tab.settings"), TAB_SIZE));
    }

    /**
     * 从资源文件夹加载 PNG 图标
     */
    private BufferedImage loadIcon(String path) {
        try (InputStream in = TabBar.class.getResourceAsStream("/assets/formacraft/" + path)) {
            if (in == null) {
                com.formacraft.FormacraftMod.LOGGER.warn("Failed to load icon: {}", path);
                return null;
            }
            return ImageIO.read(in);
        } catch (Exception e) {
            com.formacraft.FormacraftMod.LOGGER.error("Error loading icon: {}", path, e);
            return null;
        }
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
     * 绘制单个标签（完全模仿 Minecraft 原版按钮样式）
     */
    private void drawTab(DrawContext ctx, int x, int y, int width, TabInfo tab, boolean isActive, boolean isHovered) {
        int height = TAB_HEIGHT;

        // Minecraft 原版按钮颜色（参考 ButtonWidget 默认样式）
        // 未选中：0xFF80000000 (半透明黑) - 但HUD模式需要不透明
        // 选中：0xFFFFFFFF (白色高亮)
        // 悬停：稍微亮一点

        int bgColor;
        if (isActive) {
            bgColor = 0xFFFFFFFF; // 激活：白色背景
        } else if (isHovered) {
            bgColor = 0xFFD0D0D0; // 悬停：浅灰色
        } else {
            bgColor = 0xFFA0A0A0; // 未选中：深灰色
        }
        ctx.fill(x, y, x + width, y + height, bgColor);

        // Minecraft 原版边框样式（3D效果）
        // 顶部和左侧：白色高光
        // 底部和右侧：深色阴影
        int lightBorder = 0xFFFFFFFF;
        int darkBorder = 0xFF555555;

        ctx.fill(x, y, x + width, y + 1, lightBorder);
        ctx.fill(x, y, x + 1, y + height, lightBorder);
        ctx.fill(x, y + height - 1, x + width, y + height, darkBorder);
        ctx.fill(x + width - 1, y, x + width, y + height, darkBorder);

        // 激活状态：底部黑色下划线（原版标签特征）
        if (isActive) {
            ctx.fill(x, y + height - 2, x + width, y + height - 1, 0xFF000000);
        }

        // 渲染 PNG 图标（居中）
        if (tab.icon != null) {
            int iconSize = 12; // 图标稍微小一点，留出边距
            int iconX = x + (width - iconSize) / 2;
            int iconY = y + (height - iconSize) / 2;
            ImageRenderer.renderScaled(ctx, tab.icon, iconX, iconY, iconSize, iconSize);
        }
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
