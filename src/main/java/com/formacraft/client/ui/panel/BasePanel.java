package com.formacraft.client.ui.panel;

import com.formacraft.client.ui.UiTheme;
import com.formacraft.client.ui.widget.HudClickSupport;
import com.formacraft.client.ui.widget.TabBar;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.text.Text;

import java.util.List;

/**
 * 面板基类
 * 所有面板都应该继承此类
 * 固定左侧栏模式：支持折叠 / 展开（宽度动画 + 内容淡入淡出）
 */
public abstract class BasePanel {
    public void drawContentBackgroundPublic(DrawContext ctx) { drawContentBackground(ctx); }
    public void drawTooltipCompatPublic(DrawContext ctx, List<? extends Text> lines, int mx, int my) {
        @SuppressWarnings("unchecked") List<Text> cast = (List<Text>) (List<?>) lines;
        drawTooltipCompat(ctx, cast, mx, my);
    }
    public MinecraftClient client() { return client; }
    public void drawContentsPublic(DrawContext ctx) { drawContents(ctx); }
    public boolean drawCustomTooltipPublic(DrawContext ctx, double mouseX, double mouseY) {
        return drawCustomTooltip(ctx, mouseX, mouseY);
    }
    public final MinecraftClient client = MinecraftClient.getInstance();

    // 固定 UI 区域（左侧栏），X 始终为 0
    public int panelX = 0;
    public int panelY = 0;
    public int panelWidth = 160; // 实际渲染宽度（动画驱动）
    public int panelHeight = 0;
    public boolean visible = true;

    // 顶部工具栏（统一控件高度：16）
    public static final int TOOLBAR_HEIGHT = 16;
    public static final int TOOLBAR_PADDING = 2;

    // 关闭按钮区域
    public static final int CLOSE_BUTTON_SIZE = 16;
    public static final int CLOSE_BUTTON_PADDING = 0;

    // 折叠按钮区域（在关闭按钮左侧）
    public static final int COLLAPSE_BUTTON_SIZE = 16; // 与关闭按钮同尺寸
    public static final int COLLAPSE_BUTTON_PADDING = 0;

    // 左侧栏折叠状态：全局共享（所有面板共用一个宽度）
    public static boolean sidebarCollapsed = false;
    public static final int SIDEBAR_EXPANDED_WIDTH = UiTheme.SIDEBAR_EXPANDED_WIDTH;
    public static final int SIDEBAR_COLLAPSED_WIDTH = UiTheme.SIDEBAR_COLLAPSED_WIDTH;
    public static float sidebarAnimWidth = SIDEBAR_EXPANDED_WIDTH;
    public static final float SIDEBAR_ANIM_SPEED = 0.35f; // 越大越快（0~1）

    // 当前内容透明度（0 ~ 1）
    public static float contentAlpha = 1.0f;

    // 标签栏组件
    public final TabBar tabBar = new TabBar(client);

    // 工具栏按钮（使用原版 ButtonWidget，统一样式）
    public ButtonWidget closeButton;
    public ButtonWidget collapseButton;
    public ButtonWidget newChatButton;

    public void ensureToolbarWidgets() { BasePanelUi.ensureToolbarWidgets(this); }

    public double getScaledMouseX() {
        return client.mouse.getX() / client.getWindow().getScaleFactor();
    }

    public double getScaledMouseY() {
        return client.mouse.getY() / client.getWindow().getScaleFactor();
    }

    // --------------------------------------------------------------------
    // 渲染主入口
    // --------------------------------------------------------------------
    public void render(DrawContext ctx) { BasePanelUi.render(this, ctx); }

    // --------------------------------------------------------------------
    // 工具函数：clamp & smoothstep
    // --------------------------------------------------------------------
    public static float clamp01(float v) {
        if (v < 0.0f) return 0.0f;
        return Math.min(v, 1.0f);
    }

    /**
     * smoothstep(edge0, edge1, x)
     * x <= edge0 → 0
     * x >= edge1 → 1
     * 中间使用平滑插值 3t^2 - 2t^3
     */
    public static float smoothstep(float x) {
        float t = clamp01((x - (float) 0.15) / ((float) 0.8 - (float) 0.15));
        return t * t * (3.0f - 2.0f * t);
    }

    public void drawContentBackground(DrawContext ctx) {
        UiTheme.drawContentBackground(ctx, panelX, getContentY(), panelWidth, panelY + panelHeight);
    }

    public int contentScissorLeft() {
        return UiTheme.contentInnerLeft(panelX);
    }

    public int contentScissorRight() {
        return UiTheme.contentInnerRight(panelX, panelWidth);
    }

    public record ToolbarLayout(int closeX, int closeY, int collapseX, int newChatX, int buttonAreaWidth) {}

    /** 工具栏右侧按钮布局（含 Chat 新建对话按钮占位） */
    public ToolbarLayout computeToolbarLayout() { return BasePanelUi.computeToolbarLayout(this); }

    // --------------------------------------------------------------------
    // 背景与边框
    // --------------------------------------------------------------------
    /**
     * 早期背景占位；展开态最终边框在工具栏之后由 {@link #drawExpandedPanelBorder} 补绘，
     * 以便轮廓贴合激活标签而不包住未激活标签/工具按钮。
     */
    protected void drawBackground(DrawContext ctx) {
        // no-op: border is drawn after chrome
    }

    /**
     * 灰色轮廓：内容区 + 激活标签的上/左/右；未激活标签与右侧按钮在轮廓外。
     */
    public void drawExpandedPanelBorder(DrawContext ctx) { BasePanelUi.drawExpandedPanelBorder(this, ctx); }

    // --------------------------------------------------------------------
    // 折叠模式下的小手柄（只画一个窄条 + ">" 图标）
    // --------------------------------------------------------------------
    public void drawCollapsedHandle(DrawContext ctx) { BasePanelUi.drawCollapsedHandle(this, ctx); }

    // --------------------------------------------------------------------
    // 工具栏（顶部 Tabs + 关闭按钮 + 折叠按钮）
    // --------------------------------------------------------------------
    public void drawToolbar(DrawContext ctx) { BasePanelUi.drawToolbar(this, ctx); }

    public void drawNewChatButton(DrawContext ctx) { BasePanelUi.drawNewChatButton(this, ctx); }

    // --------------------------------------------------------------------
    // 绘制悬停提示（使用 Minecraft 原生样式）
    // --------------------------------------------------------------------
    public void drawTooltip(DrawContext ctx) { BasePanelUi.drawTooltip(this, ctx); }

    /**
     * Tooltip 渲染兼容：Screen 打开时走原版 drawTooltip；HUD（currentScreen==null）时手动绘制。
     */
    public final void drawTooltipCompat(DrawContext ctx, List<Text> lines, int mouseX, int mouseY) {
        UiTheme.drawTooltip(ctx, client, lines, mouseX, mouseY);
    }

    /** HUD 模式下为 ClickableWidget 补 tooltip（Screen 模式仍由原版 widget 处理）。 */
    public boolean drawWidgetTooltip(DrawContext ctx, double mouseX, double mouseY,
                                        ClickableWidget widget, Text tooltip) {
        if (client.currentScreen != null || widget == null || !widget.visible || !widget.isMouseOver(mouseX, mouseY)) {
            return false;
        }
        if (tooltip == null) {
            return false;
        }
        drawTooltipCompat(ctx, java.util.Collections.singletonList(tooltip), (int) mouseX, (int) mouseY);
        return true;
    }

    /** 使用 widget 当前 message 作为 HUD tooltip 文案。 */
    public boolean drawWidgetMessageTooltip(DrawContext ctx, double mouseX, double mouseY, ClickableWidget widget) {
        if (widget == null) {
            return false;
        }
        return drawWidgetTooltip(ctx, mouseX, mouseY, widget, widget.getMessage());
    }

    /**
     * 子类可以重写此方法来处理自定义的 tooltip
     * @param ctx 绘制上下文
     * @param mouseX 鼠标 X 坐标（已缩放）
     * @param mouseY 鼠标 Y 坐标（已缩放）
     * @return 如果处理了 tooltip 返回 true，否则返回 false
     */
    protected boolean drawCustomTooltip(DrawContext ctx, double mouseX, double mouseY) {
        return false; // 默认不处理
    }

    // --------------------------------------------------------------------
    // 关闭按钮（右上角 X）
    // --------------------------------------------------------------------
    public void drawCloseButton(DrawContext ctx) { BasePanelUi.drawCloseButton(this, ctx); }

    // --------------------------------------------------------------------
    // 折叠按钮（在关闭按钮左侧，图标 "<" / ">"）
    // --------------------------------------------------------------------
    public void drawCollapseButton(DrawContext ctx) { BasePanelUi.drawCollapseButton(this, ctx); }

    // --------------------------------------------------------------------
    // 子类内容绘制
    // --------------------------------------------------------------------
    protected abstract void drawContents(DrawContext ctx);

    // --------------------------------------------------------------------
    // 输入处理
    // --------------------------------------------------------------------
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return BasePanelUi.mouseClicked(this, mouseX, mouseY, button);
    }

    public void keyPressed(int keyCode) {}

    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        keyPressed(keyCode);
    }

    public void charTyped(char chr) {}

    public void mouseScrolled(double mouseX, double mouseY, double amount) {}

    /**
     * 鼠标拖拽事件（HUD 模式下由 MouseMixin/InputRouter 转发）。
     * <p>
     * 语义与 Screen#mouseDragged 类似：在按住鼠标按钮移动时持续触发。
     */
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) { return false; }

    /**
     * 鼠标释放事件（HUD 模式下由 MouseMixin/InputRouter 转发）。
     */
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));
        return HudClickSupport.release(click);
    }

    /**
     * 当前是否有输入控件处于焦点（供 ESC 取消焦点等逻辑查询）。
     * 注意：键盘路由不再依赖此项——鼠标离开 UI 后键盘一律交还游戏。
     */
    public boolean wantsKeyboardInput() { return false; }

    /**
     * 取消面板内所有文本输入焦点。
     * 鼠标离开 UI、或点击非输入区域时应调用。
     */
    public void blurKeyboardFocus() {}

    /**
     * 检查鼠标是否在面板内
     */
    public boolean isMouseOver(double mouseX, double mouseY) {
        return mouseX >= panelX && mouseX <= panelX + panelWidth &&
               mouseY >= panelY && mouseY <= panelY + panelHeight;
    }

    /**
     * 是否处于“可交互区域”（兼容旧调用方：InputRouter）
     * <p>
     * 语义：只要鼠标在当前面板矩形范围内（包含折叠后的窄条），就视为 UI 接管输入。
     */
    public boolean isInteractiveArea(double mouseX, double mouseY) {
        return visible && isMouseOver(mouseX, mouseY);
    }

    /**
     * 确保布局信息已初始化（兼容旧调用方）
     */
    public void ensureLayout() {
        if (panelHeight <= 0 && client != null && client.getWindow() != null) {
            panelHeight = client.getWindow().getScaledHeight();
        }
    }

    public int getPanelX() { return panelX; }
    public int getPanelY() { return panelY; }
    public int getPanelWidth() { return panelWidth; }
    public int getPanelHeight() { return panelHeight; }
    public int getContentY() { return panelY + TOOLBAR_HEIGHT; }
    public int getContentHeight() { return panelHeight - TOOLBAR_HEIGHT; }

    public static boolean isSidebarCollapsed() {
        return sidebarCollapsed;
    }
}
