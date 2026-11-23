package com.formacraft.client.ui.panel;

import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.ui.FormacraftUIState;
import com.formacraft.client.ui.FormaCraftHudOverlay;
import com.formacraft.client.ui.UiTheme;
import com.formacraft.client.ui.widget.HudClickSupport;
import com.formacraft.client.ui.widget.TabBar;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.text.Text;

/** Chrome / render / click helpers extracted from BasePanel (behavior-preserving). */
final class BasePanelUi {
    private BasePanelUi() {}

    static void ensureToolbarWidgets(BasePanel host) {
        if (host.closeButton != null) return;
        host.closeButton = ButtonWidget.builder(Text.literal("X"), b -> FormacraftUIState.close())
                .dimensions(0, 0, BasePanel.CLOSE_BUTTON_SIZE, BasePanel.CLOSE_BUTTON_SIZE)
                .build();
        host.collapseButton = ButtonWidget.builder(Text.literal("<"), b -> BasePanel.sidebarCollapsed = !BasePanel.sidebarCollapsed)
                .dimensions(0, 0, BasePanel.COLLAPSE_BUTTON_SIZE, BasePanel.COLLAPSE_BUTTON_SIZE)
                .build();
        host.newChatButton = ButtonWidget.builder(Text.literal("+"), b -> {
                    if (!FormaCraftHudOverlay.ensurePanelsReady()) return;
                    if (FormaCraftHudOverlay.CHAT_PANEL == null) return;
                    FormaCraftHudOverlay.CHAT_PANEL.startNewConversation();
                })
                .dimensions(0, 0, BasePanel.CLOSE_BUTTON_SIZE, BasePanel.CLOSE_BUTTON_SIZE)
                .build();
    }

    static BasePanel.ToolbarLayout computeToolbarLayout(BasePanel host) {
        int closeX = host.panelX + host.panelWidth - BasePanel.CLOSE_BUTTON_SIZE - BasePanel.CLOSE_BUTTON_PADDING;
        int closeY = host.panelY + BasePanel.CLOSE_BUTTON_PADDING;
        int collapseX = closeX - BasePanel.COLLAPSE_BUTTON_SIZE - BasePanel.COLLAPSE_BUTTON_PADDING;
        int newChatX = collapseX - BasePanel.CLOSE_BUTTON_SIZE - BasePanel.COLLAPSE_BUTTON_PADDING;
        int buttonAreaWidth = BasePanel.CLOSE_BUTTON_SIZE + BasePanel.COLLAPSE_BUTTON_SIZE + BasePanel.COLLAPSE_BUTTON_PADDING;
        if (FormaCraftHudOverlay.activePanel == PanelType.CHAT && host.panelWidth >= BasePanel.SIDEBAR_EXPANDED_WIDTH / 2) {
            buttonAreaWidth += BasePanel.CLOSE_BUTTON_SIZE + BasePanel.COLLAPSE_BUTTON_PADDING;
        }
        return new BasePanel.ToolbarLayout(closeX, closeY, collapseX, newChatX, buttonAreaWidth);
    }

    static void render(BasePanel host, DrawContext ctx) {
        if (!host.visible) return;

        host.panelHeight = host.client.getWindow().getScaledHeight();

        int targetWidth = BasePanel.sidebarCollapsed ? BasePanel.SIDEBAR_COLLAPSED_WIDTH : BasePanel.SIDEBAR_EXPANDED_WIDTH;

        BasePanel.sidebarAnimWidth += (targetWidth - BasePanel.sidebarAnimWidth) * BasePanel.SIDEBAR_ANIM_SPEED;
        if (Math.abs(BasePanel.sidebarAnimWidth - targetWidth) < 0.5f) {
            BasePanel.sidebarAnimWidth = targetWidth;
        }
        host.panelWidth = Math.round(BasePanel.sidebarAnimWidth);

        float progress = (BasePanel.sidebarAnimWidth - BasePanel.SIDEBAR_COLLAPSED_WIDTH) /
                         (float) (BasePanel.SIDEBAR_EXPANDED_WIDTH - BasePanel.SIDEBAR_COLLAPSED_WIDTH);
        progress = BasePanel.clamp01(progress);

        BasePanel.contentAlpha = BasePanel.smoothstep(progress);

        host.drawBackground(ctx);

        if (BasePanel.sidebarCollapsed) {
            drawCollapsedHandle(host, ctx);
            UiTheme.drawPanelBorder(ctx, host.panelX, host.panelY, host.panelX + host.panelWidth, host.panelY + host.panelHeight);
            if (host.visible) {
                drawTooltip(host, ctx);
            }
            return;
        }

        if (progress > 0.1f) {
            host.drawContentsPublic(ctx);
        }

        if (BasePanel.contentAlpha < 1.0f) {
            int alphaInt = (int) ((1.0f - BasePanel.contentAlpha) * 180);
            alphaInt = Math.max(0, Math.min(255, alphaInt));
            int color = (alphaInt << 24);
            int contentTop = host.panelY + BasePanel.TOOLBAR_HEIGHT;
            ctx.fill(host.panelX, contentTop, host.panelX + host.panelWidth, host.panelY + host.panelHeight, color);
        }

        drawToolbar(host, ctx);
        drawExpandedPanelBorder(host, ctx);

        if (host.visible) {
            drawTooltip(host, ctx);
        }
    }

    static void drawExpandedPanelBorder(BasePanel host, DrawContext ctx) {
        int contentTop = host.panelY + BasePanel.TOOLBAR_HEIGHT - 1;
        int x1 = host.panelX + host.panelWidth;
        int y1 = host.panelY + host.panelHeight;
        TabBar.TabBounds active = host.tabBar.getActiveTabBounds();
        if (active != null) {
            UiTheme.drawTabbedPanelBorder(ctx, host.panelX, contentTop, x1, y1,
                    active.x(), active.y(), active.width());
        } else {
            UiTheme.drawPanelBorder(ctx, host.panelX, contentTop, x1, y1);
        }
    }

    static void drawCollapsedHandle(BasePanel host, DrawContext ctx) {
        int x0 = host.panelX;
        int x1 = host.panelX + host.panelWidth;
        int h = host.panelHeight;

        ctx.fill(x0, host.panelY, x1, host.panelY + h, 0xAA111111);

        String icon = ">";
        int textWidth = host.client.textRenderer.getWidth(icon);
        int cx = x0 + (host.panelWidth - textWidth) / 2;
        int cy = host.panelY + h / 2 - host.client.textRenderer.fontHeight / 2;

        ctx.drawTextWithShadow(host.client.textRenderer, icon, cx, cy, 0xFFFFFFFF);
    }

    static void drawToolbar(BasePanel host, DrawContext ctx) {
        int bx = host.panelX;
        int by = host.panelY;
        int bw = host.panelWidth;
        int bh = BasePanel.TOOLBAR_HEIGHT;

        ctx.fill(bx, by, bx + bw, by + bh, UiTheme.TOOLBAR_BG);

        BasePanel.ToolbarLayout layout = computeToolbarLayout(host);
        int tabBarWidth = Math.max(0, bw - layout.buttonAreaWidth() - BasePanel.TOOLBAR_PADDING * 2);

        host.tabBar.setBounds(bx + BasePanel.TOOLBAR_PADDING, by, tabBarWidth);

        double mouseX = host.client.mouse.getX() / host.client.getWindow().getScaleFactor();
        double mouseY = host.client.mouse.getY() / host.client.getWindow().getScaleFactor();
        host.tabBar.render(ctx, mouseX, mouseY);

        drawNewChatButton(host, ctx);
        drawCollapseButton(host, ctx);
        drawCloseButton(host, ctx);
    }

    static void drawNewChatButton(BasePanel host, DrawContext ctx) {
        ensureToolbarWidgets(host);
        if (FormaCraftHudOverlay.activePanel != PanelType.CHAT) {
            if (host.newChatButton != null) host.newChatButton.visible = false;
            return;
        }
        if (host.panelWidth < BasePanel.SIDEBAR_EXPANDED_WIDTH / 2) {
            if (host.newChatButton != null) host.newChatButton.visible = false;
            return;
        }

        BasePanel.ToolbarLayout layout = computeToolbarLayout(host);
        int closeY = layout.closeY();
        int newX = layout.newChatX();

        double mouseX = host.getScaledMouseX();
        double mouseY = host.getScaledMouseY();
        host.newChatButton.setPosition(newX, closeY);
        host.newChatButton.visible = true;
        host.newChatButton.active = true;
        host.newChatButton.render(ctx, (int) mouseX, (int) mouseY, 0.0f);
    }

    static void drawTooltip(BasePanel host, DrawContext ctx) {
        double mouseX = host.client.mouse.getX() / host.client.getWindow().getScaleFactor();
        double mouseY = host.client.mouse.getY() / host.client.getWindow().getScaleFactor();

        if (BasePanel.sidebarCollapsed) {
            if (mouseX >= host.panelX && mouseX <= host.panelX + host.panelWidth
                    && mouseY >= host.panelY && mouseY <= host.panelY + host.panelHeight) {
                host.drawTooltipCompat(ctx,
                        java.util.Collections.singletonList(Text.translatable("formacraft.button.expand")),
                        (int) mouseX, (int) mouseY);
            }
            return;
        }

        if (host.drawCustomTooltipPublic(ctx, mouseX, mouseY)) {
            return;
        }

        Text tooltip = host.tabBar.getHoveredTooltip(mouseX, mouseY);
        if (tooltip != null) {
            java.util.List<Text> tooltipLines = java.util.Collections.singletonList(tooltip);
            host.drawTooltipCompat(ctx, tooltipLines, (int) mouseX, (int) mouseY);
            return;
        }

        ensureToolbarWidgets(host);
        BasePanel.ToolbarLayout layout = computeToolbarLayout(host);
        int closeX = layout.closeX();
        int closeY = layout.closeY();
        int collapseX = layout.collapseX();
        int newChatX = layout.newChatX();

        if (host.panelWidth >= BasePanel.CLOSE_BUTTON_SIZE) {
            host.closeButton.setPosition(closeX, closeY);
            host.closeButton.visible = true;
            if (host.closeButton.isMouseOver(mouseX, mouseY)) {
                host.drawTooltipCompat(ctx,
                        java.util.Collections.singletonList(Text.translatable("formacraft.button.close")),
                        (int) mouseX, (int) mouseY);
                return;
            }
        }

        if (!BasePanel.sidebarCollapsed && host.panelWidth >= BasePanel.SIDEBAR_EXPANDED_WIDTH / 2) {
            host.collapseButton.setPosition(collapseX, closeY);
            host.collapseButton.visible = true;
            if (host.collapseButton.isMouseOver(mouseX, mouseY)) {
                host.drawTooltipCompat(ctx,
                        java.util.Collections.singletonList(Text.translatable("formacraft.button.collapse")),
                        (int) mouseX, (int) mouseY);
            }
        }

        if (!BasePanel.sidebarCollapsed && host.panelWidth >= BasePanel.SIDEBAR_EXPANDED_WIDTH / 2
                && FormaCraftHudOverlay.activePanel == PanelType.CHAT) {
            host.newChatButton.setPosition(newChatX, closeY);
            host.newChatButton.visible = true;
            if (host.newChatButton.isMouseOver(mouseX, mouseY)) {
                host.drawTooltipCompat(ctx,
                        java.util.Collections.singletonList(Text.translatable("formacraft.chat.new_conversation.tooltip")),
                        (int) mouseX, (int) mouseY);
            }
        }
    }

    static void drawCloseButton(BasePanel host, DrawContext ctx) {
        ensureToolbarWidgets(host);
        int closeX = host.panelX + host.panelWidth - BasePanel.CLOSE_BUTTON_SIZE - BasePanel.CLOSE_BUTTON_PADDING;
        int closeY = host.panelY + BasePanel.CLOSE_BUTTON_PADDING;

        if (host.panelWidth < BasePanel.CLOSE_BUTTON_SIZE) {
            if (host.closeButton != null) host.closeButton.visible = false;
            return;
        }

        double mouseX = host.getScaledMouseX();
        double mouseY = host.getScaledMouseY();
        host.closeButton.setPosition(closeX, closeY);
        host.closeButton.visible = true;
        host.closeButton.active = true;
        host.closeButton.render(ctx, (int) mouseX, (int) mouseY, 0.0f);
    }

    static void drawCollapseButton(BasePanel host, DrawContext ctx) {
        ensureToolbarWidgets(host);
        if (host.panelWidth < BasePanel.SIDEBAR_EXPANDED_WIDTH / 2) {
            if (host.collapseButton != null) host.collapseButton.visible = false;
            return;
        }

        int closeX = host.panelX + host.panelWidth - BasePanel.CLOSE_BUTTON_SIZE - BasePanel.CLOSE_BUTTON_PADDING;
        int closeY = host.panelY + BasePanel.CLOSE_BUTTON_PADDING;

        int collapseX = closeX - BasePanel.COLLAPSE_BUTTON_SIZE - BasePanel.COLLAPSE_BUTTON_PADDING;

        double mouseX = host.getScaledMouseX();
        double mouseY = host.getScaledMouseY();
        String icon = BasePanel.sidebarCollapsed ? ">" : "<";
        host.collapseButton.setMessage(Text.literal(icon));
        host.collapseButton.setPosition(collapseX, closeY);
        host.collapseButton.visible = !BasePanel.sidebarCollapsed;
        host.collapseButton.active = !BasePanel.sidebarCollapsed;
        if (!BasePanel.sidebarCollapsed) {
            host.collapseButton.render(ctx, (int) mouseX, (int) mouseY, 0.0f);
        }
    }

    static boolean mouseClicked(BasePanel host, double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        if (!host.isMouseOver(mouseX, mouseY)) return false;

        if (BasePanel.sidebarCollapsed) {
            BasePanel.sidebarCollapsed = false;
            return true;
        }

        ensureToolbarWidgets(host);
        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));
        BasePanel.ToolbarLayout layout = computeToolbarLayout(host);
        int closeX = layout.closeX();
        int closeY = layout.closeY();
        int collapseX = layout.collapseX();
        int newChatX = layout.newChatX();

        if (host.panelWidth >= BasePanel.CLOSE_BUTTON_SIZE) {
            host.closeButton.setPosition(closeX, closeY);
            host.closeButton.visible = true;
            host.closeButton.active = true;
            if (HudClickSupport.click(host.closeButton, click)) return true;
        }
        if (!BasePanel.sidebarCollapsed && host.panelWidth >= BasePanel.SIDEBAR_EXPANDED_WIDTH / 2) {
            host.collapseButton.setPosition(collapseX, closeY);
            host.collapseButton.visible = true;
            host.collapseButton.active = true;
            if (HudClickSupport.click(host.collapseButton, click)) return true;
        }
        if (!BasePanel.sidebarCollapsed && host.panelWidth >= BasePanel.SIDEBAR_EXPANDED_WIDTH / 2
                && FormaCraftHudOverlay.activePanel == PanelType.CHAT) {
            host.newChatButton.setPosition(newChatX, closeY);
            host.newChatButton.visible = true;
            host.newChatButton.active = true;
            if (HudClickSupport.click(host.newChatButton, click)) return true;
        }

        PanelType clickedTab = host.tabBar.handleMouseClick(mouseX, mouseY, button);
        if (clickedTab != null) {
            host.blurKeyboardFocus();
            if (FormaCraftHudOverlay.activePanel == PanelType.COMPONENT_CAPTURE
                    && clickedTab != PanelType.COMPONENT_CAPTURE) {
                ComponentTool.INSTANCE.getState().cancelCapture();
            }
            FormaCraftHudOverlay.activePanel = clickedTab;
            return true;
        }

        return false;
    }
}
