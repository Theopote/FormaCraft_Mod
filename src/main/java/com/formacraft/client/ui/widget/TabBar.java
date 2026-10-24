package com.formacraft.client.ui.widget;

import com.formacraft.client.ui.FormaCraftHudOverlay;
import com.formacraft.client.ui.UiTheme;
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
 * 标签栏组件（共享深色主题）
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
    /** Last active tab bounds from {@link #render}, or null if none visible. */
    @Nullable
    private TabBounds activeBounds;

    /**
     * 标签信息
     *
     * @param icon    PNG 图标
     * @param tooltip 悬停提示
     * @param width   标签宽度
     */
    public record TabInfo(PanelType type, BufferedImage icon, Text tooltip, int width) {
    }

    /** Active tab rectangle used by the panel chrome outline. */
    public record TabBounds(int x, int y, int width, int height) {
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
     * 渲染标签栏（先画未选中，最后画选中，实现层级效果）
     */
    public void render(DrawContext ctx, double mouseX, double mouseY) {
        int currentX = x + TAB_PADDING;
        TabInfo activeTab = null;
        int activeX = 0;
        activeBounds = null;

        // 第一遍：只画未选中
        for (TabInfo tab : tabs) {
            if (!tabFits(currentX, tab)) break;
            boolean isActive = FormaCraftHudOverlay.activePanel == tab.type;
            if (isActive) {
                activeTab = tab;
                activeX = currentX;
            } else {
                boolean isHovered = isMouseOverTab(currentX, tab, mouseX, mouseY);
                drawTab(ctx, currentX, y + TAB_PADDING, tab.width, tab, false, isHovered);
            }
            currentX += tab.width + TAB_GAP;
        }

        // Content-top seam is drawn by the panel chrome (with a gap under the active tab).

        // 最后画选中标签（覆盖在上面；外轮廓由面板灰边框统一绘制）
        if (activeTab != null) {
            activeBounds = new TabBounds(activeX, y + TAB_PADDING, activeTab.width, TAB_HEIGHT);
            boolean isHovered = isMouseOverTab(activeX, activeTab, mouseX, mouseY);
            drawTab(ctx, activeX, y + TAB_PADDING, activeTab.width, activeTab, true, isHovered);
        }
    }

    /** Bounds of the visible active tab after the last {@link #render}, or null. */
    @Nullable
    public TabBounds getActiveTabBounds() {
        return activeBounds;
    }

    /**
     * 绘制单个标签。
     * 未选中：自带标签边框色；选中：只画内容，轮廓交给面板整体灰边框。
     */
    private void drawTab(DrawContext ctx, int x, int y, int width, TabInfo tab, boolean isActive, boolean isHovered) {
        int height = TAB_HEIGHT;
        // Same top edge as the screen; inactive ends on the content seam (outside the frame).
        int top = y;
        int bottom = y + height - (isActive ? 0 : 1);
        int bg = isActive ? UiTheme.TAB_SELECTED_BG
                : isHovered ? UiTheme.TAB_HOVER_BG : UiTheme.TAB_UNSELECTED_BG;
        if (!isActive) {
            ctx.fill(x, top, x + width, bottom, bg);
            ctx.fill(x, top, x + width, top + 1, UiTheme.BORDER);
            ctx.fill(x, top + 1, x + 1, bottom - 1, UiTheme.BORDER);
            ctx.fill(x + width - 1, top + 1, x + width, bottom, UiTheme.BORDER_SHADOW);
            ctx.fill(x, bottom - 1, x + width, bottom, UiTheme.BORDER_SHADOW);
        }
        // Active: no local border — UiTheme.drawTabbedPanelBorder draws the shared frame.

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
        return mouseX >= tabX && mouseX < tabX + tab.width &&
               mouseY >= tabY && mouseY < tabY + TAB_HEIGHT;
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
