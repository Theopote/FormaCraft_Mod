package com.formacraft.client.ui.panel;

import com.formacraft.client.ui.FormacraftUIState;
import com.formacraft.client.ui.FormaCraftHudOverlay;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * 面板基类
 * 所有面板都应该继承此类
 * 固定左侧栏模式：支持折叠 / 展开（宽度动画 + 内容淡入淡出）
 */
public abstract class BasePanel {
    protected final MinecraftClient client = MinecraftClient.getInstance();

    // 固定 UI 区域（左侧栏），X 始终为 0
    protected int panelX = 0;
    protected int panelY = 0;
    protected int panelWidth = 160; // 实际渲染宽度（动画驱动）
    protected int panelHeight = 0;
    protected boolean visible = true;

    // 顶部工具栏
    private static final int TOOLBAR_HEIGHT = 20;
    private static final int TOOLBAR_PADDING = 4;

    // 关闭按钮区域
    private static final int CLOSE_BUTTON_SIZE = 12;
    private static final int CLOSE_BUTTON_PADDING = 4;

    // 折叠按钮区域（在关闭按钮左侧）
    private static final int COLLAPSE_BUTTON_SIZE = 10;
    private static final int COLLAPSE_BUTTON_PADDING = 2;

    // 左侧栏折叠状态：全局共享（所有面板共用一个宽度）
    private static boolean sidebarCollapsed = false;
    private static final int SIDEBAR_EXPANDED_WIDTH = 160;
    private static final int SIDEBAR_COLLAPSED_WIDTH = 12;
    private static float sidebarAnimWidth = SIDEBAR_EXPANDED_WIDTH;
    private static final float SIDEBAR_ANIM_SPEED = 0.35f; // 越大越快（0~1）

    // 当前内容透明度（0 ~ 1）
    private static float contentAlpha = 1.0f;

    // --------------------------------------------------------------------
    // 渲染主入口
    // --------------------------------------------------------------------
    public void render(DrawContext ctx) {
        if (!visible) return;

        // 面板高度 = 当前窗口高度
        this.panelHeight = client.getWindow().getScaledHeight();

        // 目标宽度：根据折叠状态选择
        int targetWidth = sidebarCollapsed ? SIDEBAR_COLLAPSED_WIDTH : SIDEBAR_EXPANDED_WIDTH;

        // 宽度缓动动画：current += (target - current) * speed
        sidebarAnimWidth += (targetWidth - sidebarAnimWidth) * SIDEBAR_ANIM_SPEED;
        if (Math.abs(sidebarAnimWidth - targetWidth) < 0.5f) {
            sidebarAnimWidth = targetWidth;
        }
        this.panelWidth = Math.round(sidebarAnimWidth);

        // progress 0~1：当前展开进度
        float progress = (sidebarAnimWidth - SIDEBAR_COLLAPSED_WIDTH) /
                         (float) (SIDEBAR_EXPANDED_WIDTH - SIDEBAR_COLLAPSED_WIDTH);
        progress = clamp01(progress);

        // 使用 smoothstep 让透明度变化更柔和
        // 0.15 之前完全看不到，0.8 之后完全不遮罩
        contentAlpha = smoothstep(progress);

        // 背景（无论折叠与否，左侧都有一条区域）
        drawBackground(ctx);

        // 如果处于完全折叠状态，且宽度接近 collapsed 宽度，只绘制窄条手柄
        if (sidebarCollapsed && panelWidth <= SIDEBAR_COLLAPSED_WIDTH + 1) {
            drawCollapsedHandle(ctx);
            return;
        }

        // 展开或动画中：绘制工具栏和内容
        // 为避免刚展开时布局挤压，在 progress 很小的时候可以只显示工具栏/空背景
        if (progress > 0.1f) {
            drawToolbar(ctx);
            drawContents(ctx);
        } else {
            // 稍微画一下顶部条，让用户知道有东西在出来
            drawToolbar(ctx);
        }

        // 在内容上叠加一层黑色遮罩，实现淡入淡出效果
        if (contentAlpha < 1.0f) {
            int alphaInt = (int) ((1.0f - contentAlpha) * 180); // 最大 180/255 的黑遮罩
            alphaInt = Math.max(0, Math.min(255, alphaInt));
            int color = (alphaInt << 24); // ARGB: alpha + 0x000000
            ctx.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, color);
        }
    }

    // --------------------------------------------------------------------
    // 工具函数：clamp & smoothstep
    // --------------------------------------------------------------------
    private static float clamp01(float v) {
        if (v < 0.0f) return 0.0f;
        return Math.min(v, 1.0f);
    }

    /**
     * smoothstep(edge0, edge1, x)
     * x <= edge0 → 0
     * x >= edge1 → 1
     * 中间使用平滑插值 3t^2 - 2t^3
     */
    private static float smoothstep(float x) {
        float t = clamp01((x - (float) 0.15) / ((float) 0.8 - (float) 0.15));
        return t * t * (3.0f - 2.0f * t);
    }

    // --------------------------------------------------------------------
    // 背景与边框
    // --------------------------------------------------------------------
    /**
     * 绘制半透明背景和边框
     */
    protected void drawBackground(DrawContext ctx) {
        int x0 = panelX;
        int y0 = panelY;
        int x1 = panelX + panelWidth;
        int y1 = panelY + panelHeight;

        // 原版 UI 的渐变背景
        ctx.fillGradient(x0, y0, x1, y1, 0xC0101010, 0xD0101010);

        // 原版风格的描边（上/左亮，下/右暗）
        int light = 0xFFFFFFFF;
        int dark = 0xFF000000;
        ctx.fill(x0, y0, x1, y0 + 1, light);
        ctx.fill(x0, y1 - 1, x1, y1, dark);
        ctx.fill(x0, y0, x0 + 1, y1, light);
        ctx.fill(x1 - 1, y0, x1, y1, dark);
    }

    // --------------------------------------------------------------------
    // 折叠模式下的小手柄（只画一个窄条 + ">" 图标）
    // --------------------------------------------------------------------
    private void drawCollapsedHandle(DrawContext ctx) {
        int x0 = panelX;
        int x1 = panelX + panelWidth;
        int h = panelHeight;

        // 再盖一层更深的背景，突出它是可交互手柄
        ctx.fill(x0, panelY, x1, panelY + h, 0xAA111111);

        // 在中间画一个 ">" 提示
        String icon = ">";
        int textWidth = client.textRenderer.getWidth(icon);
        int cx = x0 + (panelWidth - textWidth) / 2;
        int cy = panelY + h / 2 - client.textRenderer.fontHeight / 2;

        ctx.drawTextWithShadow(client.textRenderer, icon, cx, cy, 0xFFFFFFFF);
    }

    // --------------------------------------------------------------------
    // 工具栏（顶部 Tabs + 关闭按钮 + 折叠按钮）
    // --------------------------------------------------------------------
    protected void drawToolbar(DrawContext ctx) {
        int bx = panelX;
        int by = panelY;
        int bw = panelWidth;
        int bh = TOOLBAR_HEIGHT;

        // 顶部工具栏背景
        ctx.fill(bx, by, bx + bw, by + bh, 0xAA1A1A1A);
        // 顶部与内容的分隔线
        ctx.fill(bx, by + bh - 1, bx + bw, by + bh, 0x66FFFFFF);

        int x = bx + TOOLBAR_PADDING;
        drawTab(ctx, "Chat", PanelType.CHAT, x, by + 4);
        x += 30;
        drawTab(ctx, "Blueprint", PanelType.BLUEPRINT, x, by + 4);
        x += 45;
        drawTab(ctx, "Settings", PanelType.SETTINGS, x, by + 4);
        x += 40;
        drawTab(ctx, "History", PanelType.HISTORY, x, by + 4);

        // 先画折叠按钮，再画关闭按钮
        drawCollapseButton(ctx);
        drawCloseButton(ctx);
    }

    // --------------------------------------------------------------------
    // 关闭按钮（右上角 X）
    // --------------------------------------------------------------------
    private void drawCloseButton(DrawContext ctx) {
        int closeX = panelX + panelWidth - CLOSE_BUTTON_SIZE - CLOSE_BUTTON_PADDING;
        int closeY = panelY + CLOSE_BUTTON_PADDING;

        // 面板太窄时不绘制（避免折叠动画早期挤成一坨）
        if (panelWidth < CLOSE_BUTTON_SIZE + CLOSE_BUTTON_PADDING + 6) {
            return;
        }

        drawMinecraftButton(ctx, closeX, closeY, CLOSE_BUTTON_SIZE, CLOSE_BUTTON_SIZE, Text.literal("X"), false);
    }

    private boolean isMouseOverCloseButton(double mouseX, double mouseY) {
        if (panelWidth < CLOSE_BUTTON_SIZE + CLOSE_BUTTON_PADDING + 6) {
            return false;
        }
        int closeX = panelX + panelWidth - CLOSE_BUTTON_SIZE - CLOSE_BUTTON_PADDING;
        int closeY = panelY + CLOSE_BUTTON_PADDING;

        return mouseX >= closeX && mouseX <= closeX + CLOSE_BUTTON_SIZE &&
               mouseY >= closeY && mouseY <= closeY + CLOSE_BUTTON_SIZE;
    }

    // --------------------------------------------------------------------
    // 折叠按钮（在关闭按钮左侧，图标 "<" / ">"）
    // --------------------------------------------------------------------
    private void drawCollapseButton(DrawContext ctx) {
        // 面板太窄时不绘制（折叠手柄由 collapsedHandle 负责）
        if (panelWidth < SIDEBAR_EXPANDED_WIDTH / 2) {
            return;
        }

        int closeX = panelX + panelWidth - CLOSE_BUTTON_SIZE - CLOSE_BUTTON_PADDING;
        int closeY = panelY + CLOSE_BUTTON_PADDING;

        int collapseX = closeX - COLLAPSE_BUTTON_SIZE - COLLAPSE_BUTTON_PADDING;
        int collapseY = closeY + 1;
        int h = COLLAPSE_BUTTON_SIZE - 2;

        String icon = sidebarCollapsed ? ">" : "<";
        drawMinecraftButton(ctx, collapseX, collapseY, COLLAPSE_BUTTON_SIZE, h, Text.literal(icon), false);
    }

    private boolean isMouseOverCollapseButton(double mouseX, double mouseY) {
        if (panelWidth < SIDEBAR_EXPANDED_WIDTH / 2 || sidebarCollapsed) {
            // 折叠状态下，这个按钮不显示，由整条边栏手柄控制
            return false;
        }

        int closeX = panelX + panelWidth - CLOSE_BUTTON_SIZE - CLOSE_BUTTON_PADDING;
        int closeY = panelY + CLOSE_BUTTON_PADDING;

        int collapseX = closeX - COLLAPSE_BUTTON_SIZE - COLLAPSE_BUTTON_PADDING;
        int collapseY = closeY + 1;
        int h = COLLAPSE_BUTTON_SIZE - 2;

        return mouseX >= collapseX && mouseX <= collapseX + COLLAPSE_BUTTON_SIZE &&
               mouseY >= collapseY && mouseY <= collapseY + h;
    }

    // --------------------------------------------------------------------
    // 顶部标签（Tab）
    // --------------------------------------------------------------------
    private void drawTab(DrawContext ctx, String text, PanelType type, int x, int y) {
        boolean isActive = (FormaCraftHudOverlay.activePanel == type);
        int color = isActive ? 0xFFFFFFFF : 0xFFAAAAAA;

        ctx.drawText(client.textRenderer, text, x, y, color, false);

        if (isActive) {
            int textWidth = client.textRenderer.getWidth(text);
            ctx.fill(x, y + 4, x + textWidth, y + 4, 0xFFFFD700);
        }
    }

    // --------------------------------------------------------------------
    // 子类内容绘制
    // --------------------------------------------------------------------
    protected abstract void drawContents(DrawContext ctx);

    // --------------------------------------------------------------------
    // Minecraft 风格按钮
    // --------------------------------------------------------------------
    protected void drawMinecraftButton(DrawContext ctx, int x, int y, int width, int height, Text text, boolean hovered) {
        drawMinecraftButton(ctx, client, x, y, width, height, text, hovered);
    }

    public static void drawMinecraftButton(DrawContext ctx, MinecraftClient client, int x, int y, int width, int height, Text text, boolean hovered) {
        int bgColor = hovered ? 0xFF777777 : 0xFF555555;
        ctx.fill(x, y, x + width, y + height, bgColor);

        int lightBorder = 0xFFAAAAAA;
        ctx.fill(x, y, x + width, y + 1, lightBorder);
        ctx.fill(x, y, x + 1, y + height, lightBorder);

        int darkBorder = 0xFF333333;
        ctx.fill(x, y + height - 1, x + width, y + height, darkBorder);
        ctx.fill(x + width - 1, y, x + width, y + height, darkBorder);

        int textColor = hovered ? 0xFFFF00 : 0xFFFFFF;
        ctx.drawCenteredTextWithShadow(client.textRenderer, text, x + width / 2, y + (height - 8) / 2, textColor);
    }

    // --------------------------------------------------------------------
    // 输入处理
    // --------------------------------------------------------------------
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;

        // 如果完全不在面板区域内，则不处理
        if (!isMouseOver(mouseX, mouseY)) return false;

        // 折叠状态：点击窄条 = 展开
        if (sidebarCollapsed && panelWidth <= SIDEBAR_COLLAPSED_WIDTH + 1) {
            sidebarCollapsed = false;
            return true;
        }

        // 检查关闭按钮
        if (isMouseOverCloseButton(mouseX, mouseY)) {
            FormacraftUIState.close();
            return true;
        }

        // 检查折叠按钮
        if (isMouseOverCollapseButton(mouseX, mouseY)) {
            sidebarCollapsed = !sidebarCollapsed;
            return true;
        }

        // Tab 区域（顶部工具栏内）
        int by = panelY;
        if (mouseY >= by && mouseY <= by + TOOLBAR_HEIGHT) {
            int x = panelX + TOOLBAR_PADDING;
            if (mouseX >= x && mouseX <= x + 30) {
                FormaCraftHudOverlay.activePanel = PanelType.CHAT;
                return true;
            }
            x += 35;
            if (mouseX >= x && mouseX <= x + 45) {
                FormaCraftHudOverlay.activePanel = PanelType.BLUEPRINT;
                return true;
            }
            x += 50;
            if (mouseX >= x && mouseX <= x + 40) {
                FormaCraftHudOverlay.activePanel = PanelType.SETTINGS;
                return true;
            }
            x += 45;
            if (mouseX >= x && mouseX <= x + 35) {
                FormaCraftHudOverlay.activePanel = PanelType.HISTORY;
                return true;
            }
        }

        return false;
    }

    public void keyPressed(int keyCode) {}

    public void keyPressed(int keyCode, int scanCode, int modifiers) {
        keyPressed(keyCode);
    }

    public void charTyped(char chr) {}

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
