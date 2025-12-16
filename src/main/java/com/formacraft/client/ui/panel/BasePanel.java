package com.formacraft.client.ui.panel;

import com.formacraft.client.ui.FormacraftUIState;
import com.formacraft.client.ui.FormaCraftHudOverlay;
import com.formacraft.client.ui.widget.TabBar;
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

    // 顶部工具栏（减小高度以匹配更小的标签）
    private static final int TOOLBAR_HEIGHT = 16;
    private static final int TOOLBAR_PADDING = 4;

    // 关闭按钮区域
    private static final int CLOSE_BUTTON_SIZE = 12;
    private static final int CLOSE_BUTTON_PADDING = 2;

    // 折叠按钮区域（在关闭按钮左侧）
    private static final int COLLAPSE_BUTTON_SIZE = 12; // 与关闭按钮同尺寸
    private static final int COLLAPSE_BUTTON_PADDING = 2;

    // 左侧栏折叠状态：全局共享（所有面板共用一个宽度）
    private static boolean sidebarCollapsed = false;
    private static final int SIDEBAR_EXPANDED_WIDTH = 160;
    private static final int SIDEBAR_COLLAPSED_WIDTH = 12;
    private static float sidebarAnimWidth = SIDEBAR_EXPANDED_WIDTH;
    private static final float SIDEBAR_ANIM_SPEED = 0.35f; // 越大越快（0~1）

    // 当前内容透明度（0 ~ 1）
    private static float contentAlpha = 1.0f;
    
    // 标签栏组件
    private final TabBar tabBar = new TabBar(client);

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
            // 折叠状态下也要显示 tooltip（用于手柄的提示）
            if (visible) {
                drawTooltip(ctx);
            }
            return;
        }

        // 展开或动画中：绘制工具栏和内容
        // 为避免刚展开时布局挤压，在 progress 很小的时候可以只显示工具栏/空背景
        drawToolbar(ctx);
        if (progress > 0.1f) {
            drawContents(ctx);
        } else {
            // 稍微画一下顶部条，让用户知道有东西在出来
        }

        // 在内容上叠加一层黑色遮罩，实现淡入淡出效果
        // 注意：遮罩不应该覆盖工具栏区域，只覆盖内容区域
        if (contentAlpha < 1.0f) {
            int alphaInt = (int) ((1.0f - contentAlpha) * 180); // 最大 180/255 的黑遮罩
            alphaInt = Math.max(0, Math.min(255, alphaInt));
            int color = (alphaInt << 24); // ARGB: alpha + 0x000000
            // 只覆盖内容区域，不覆盖工具栏
            int contentTop = panelY + TOOLBAR_HEIGHT;
            ctx.fill(panelX, contentTop, panelX + panelWidth, panelY + panelHeight, color);
        }

        // Tooltip 一定要最后画，避免被遮罩/内容覆盖
        // 只要面板可见就显示 tooltip（包括折叠状态，因为折叠时也有按钮）
        if (visible) {
            drawTooltip(ctx);
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
     * 绘制边框（仅3D边框效果，不绘制内容区背景）
     */
    protected void drawBackground(DrawContext ctx) {
        int x0 = panelX;
        int y0 = panelY;
        int x1 = panelX + panelWidth;
        int y1 = panelY + panelHeight;

        // 外层 3D 边框（上/左高光，下/右阴影）
        int outerLight = 0xFFFFFFFF;
        int outerDark = 0xFF6F6F6F;
        ctx.fill(x0, y0, x1, y0 + 1, outerLight); // top
        ctx.fill(x0, y0, x0 + 1, y1, outerLight); // left
        ctx.fill(x0, y1 - 1, x1, y1, outerDark); // bottom
        ctx.fill(x1 - 1, y0, x1, y1, outerDark); // right

        // 内层 3D 边框（再内缩 1px）
        int ix0 = x0 + 1;
        int iy0 = y0 + 1;
        int ix1 = x1 - 1;
        int iy1 = y1 - 1;
        int innerLight = 0xFFE6E6E6;
        int innerDark = 0xFF8A8A8A;
        ctx.fill(ix0, iy0, ix1, iy0 + 1, innerLight);
        ctx.fill(ix0, iy0, ix0 + 1, iy1, innerLight);
        ctx.fill(ix0, iy1 - 1, ix1, iy1, innerDark);
        ctx.fill(ix1 - 1, iy0, ix1, iy1, innerDark);
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

        // 标签区域背景（几乎不透明）
        ctx.fillGradient(bx, by, bx + bw, by + bh, 0xFFD7D7D7, 0xFFC9C9C9);

        // 顶部/底部分隔线（Minecraft 原生样式）
        ctx.fill(bx, by, bx + bw, by + 1, 0xFFFFFFFF);
        ctx.fill(bx, by + bh - 1, bx + bw, by + bh, 0xFF7A7A7A);

        // 计算标签栏可用宽度（减去按钮区域）
        int buttonAreaWidth = CLOSE_BUTTON_SIZE + CLOSE_BUTTON_PADDING * 2 + 
                             COLLAPSE_BUTTON_SIZE + COLLAPSE_BUTTON_PADDING;
        int tabBarWidth = Math.max(0, bw - buttonAreaWidth - TOOLBAR_PADDING * 2);
        
        // 设置标签栏位置并渲染
        tabBar.setBounds(bx + TOOLBAR_PADDING, by, tabBarWidth);
        
        // 获取鼠标位置（scaled坐标）
        double mouseX = client.mouse.getX() / client.getWindow().getScaleFactor();
        double mouseY = client.mouse.getY() / client.getWindow().getScaleFactor();
        tabBar.render(ctx, mouseX, mouseY);

        // 先画折叠按钮，再画关闭按钮（深色小方块）
        drawCollapseButton(ctx);
        drawCloseButton(ctx);
    }
    
    // --------------------------------------------------------------------
    // 绘制悬停提示（使用 Minecraft 原生样式）
    // --------------------------------------------------------------------
    private void drawTooltip(DrawContext ctx) {
        // 获取鼠标位置（使用正确的缩放因子）
        double mouseX = client.mouse.getX() / client.getWindow().getScaleFactor();
        double mouseY = client.mouse.getY() / client.getWindow().getScaleFactor();
        
        // 如果处于完全折叠状态，检查折叠手柄的 tooltip
        if (sidebarCollapsed && panelWidth <= SIDEBAR_COLLAPSED_WIDTH + 1) {
            // 检查鼠标是否在折叠手柄上
            if (mouseX >= panelX && mouseX <= panelX + panelWidth &&
                mouseY >= panelY && mouseY <= panelY + panelHeight) {
                Text expandTooltip = Text.translatable("formacraft.button.expand");
                java.util.List<Text> tooltipLines = java.util.Collections.singletonList(expandTooltip);
                ctx.drawTooltip(client.textRenderer, tooltipLines, (int) mouseX, (int) mouseY);
            }
            return;
        }
        
        // 先检查子类特有的 tooltip（如 ChatPanel 的发送按钮）
        if (drawCustomTooltip(ctx, mouseX, mouseY)) {
            return; // 如果子类处理了 tooltip，就不再检查其他按钮
        }
        
        // 检查标签提示（优先级最高，因为标签在按钮上方）
        Text tooltip = tabBar.getHoveredTooltip(mouseX, mouseY);
        if (tooltip != null) {
            // 使用 Minecraft 原生的 Tooltip 渲染方法
            java.util.List<Text> tooltipLines = java.util.Collections.singletonList(tooltip);
            ctx.drawTooltip(client.textRenderer, tooltipLines, (int) mouseX, (int) mouseY);
            return;
        }
        
        // 检查按钮提示（关闭按钮和折叠按钮）
        // 注意：需要确保按钮已经绘制，所以 panelWidth 必须足够大
        if (isMouseOverCloseButton(mouseX, mouseY)) {
            Text closeTooltip = Text.translatable("formacraft.button.close");
            java.util.List<Text> tooltipLines = java.util.Collections.singletonList(closeTooltip);
            ctx.drawTooltip(client.textRenderer, tooltipLines, (int) mouseX, (int) mouseY);
            return;
        }
        
        if (isMouseOverCollapseButton(mouseX, mouseY)) {
            Text collapseTooltip = Text.translatable(sidebarCollapsed ? "formacraft.button.expand" : "formacraft.button.collapse");
            java.util.List<Text> tooltipLines = java.util.Collections.singletonList(collapseTooltip);
            ctx.drawTooltip(client.textRenderer, tooltipLines, (int) mouseX, (int) mouseY);
        }
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
    private void drawCloseButton(DrawContext ctx) {
        int closeX = panelX + panelWidth - CLOSE_BUTTON_SIZE - CLOSE_BUTTON_PADDING;
        int closeY = panelY + CLOSE_BUTTON_PADDING;

        // 面板太窄时不绘制（避免折叠动画早期挤成一坨）
        if (panelWidth < CLOSE_BUTTON_SIZE + CLOSE_BUTTON_PADDING + 6) {
            return;
        }

        // 检查鼠标是否悬停在关闭按钮上
        double mouseX = client.mouse.getX() / client.getWindow().getScaleFactor();
        double mouseY = client.mouse.getY() / client.getWindow().getScaleFactor();
        boolean hovered = isMouseOverCloseButton(mouseX, mouseY);
        drawToolbarButton(ctx, closeX, closeY, CLOSE_BUTTON_SIZE, CLOSE_BUTTON_SIZE, Text.literal("X"), hovered);
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

        // 检查鼠标是否悬停在折叠按钮上
        double mouseX = client.mouse.getX() / client.getWindow().getScaleFactor();
        double mouseY = client.mouse.getY() / client.getWindow().getScaleFactor();
        boolean hovered = isMouseOverCollapseButton(mouseX, mouseY);
        String icon = sidebarCollapsed ? ">" : "<";
        drawToolbarButton(ctx, collapseX, closeY, COLLAPSE_BUTTON_SIZE, COLLAPSE_BUTTON_SIZE, Text.literal(icon), hovered);
    }

    /**
     * 工具栏按钮（使用 Minecraft 默认样式）
     */
    private void drawToolbarButton(DrawContext ctx, int x, int y, int w, int h, Text text, boolean hovered) {
        drawMinecraftButton(ctx, x, y, w, h, text, hovered);
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
    // 子类内容绘制
    // --------------------------------------------------------------------
    protected abstract void drawContents(DrawContext ctx);

    // --------------------------------------------------------------------
    // Minecraft 风格按钮
    // --------------------------------------------------------------------
    protected void drawMinecraftButton(DrawContext ctx, int x, int y, int width, int height, Text text, boolean hovered) {
        drawMinecraftButton(ctx, client, x, y, width, height, text, hovered);
    }

    /**
     * 绘制 Minecraft 原生风格按钮（使用正确的原版样式）
     */
    public static void drawMinecraftButton(DrawContext ctx, MinecraftClient client, int x, int y, int width, int height, Text text, boolean hovered) {
        // Minecraft 原版按钮颜色（更接近原版）
        // 普通状态：较亮的灰色（原版按钮不是纯黑）
        // 悬停状态：更亮的灰色
        int topColor = hovered ? 0xFF6B6B6B : 0xFF5B5B5B;
        int bottomColor = hovered ? 0xFF4B4B4B : 0xFF3B3B3B;
        ctx.fillGradient(x, y, x + width, y + height, topColor, bottomColor);

        // Minecraft 原版按钮边框（上/左亮，下/右暗，3D效果）
        // 原版按钮边框颜色更明显
        int lightBorder = hovered ? 0xFFCCCCCC : 0xFFAAAAAA;
        int darkBorder = 0xFF000000;
        
        // 上边框和左边框（亮色）
        ctx.fill(x, y, x + width, y + 1, lightBorder);
        ctx.fill(x, y, x + 1, y + height, lightBorder);
        
        // 下边框和右边框（暗色）
        ctx.fill(x, y + height - 1, x + width, y + height, darkBorder);
        ctx.fill(x + width - 1, y, x + width, y + height, darkBorder);

        // 绘制文本（必须在所有背景和边框之后绘制，确保在最上层）
        // 确保文本完美居中（水平和垂直）
        if (text != null && !text.getString().isEmpty()) {
            // 计算按钮中心点
            int centerX = x + width / 2;
            int centerY = y + height / 2;
            // 使用 drawCenteredTextWithShadow 确保完美居中
            // 注意：drawCenteredTextWithShadow 的 y 坐标是文本的顶部，需要调整
            int textY = centerY - client.textRenderer.fontHeight / 2;
            ctx.drawCenteredTextWithShadow(client.textRenderer, text, centerX, textY, 0xFFFFFFFF);
        }
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

        // Tab 区域（使用 TabBar 处理）
        PanelType clickedTab = tabBar.handleMouseClick(mouseX, mouseY, button);
        if (clickedTab != null) {
            FormaCraftHudOverlay.activePanel = clickedTab;
            return true;
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
