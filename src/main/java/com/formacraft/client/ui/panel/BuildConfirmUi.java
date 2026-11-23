package com.formacraft.client.ui.panel;

import com.formacraft.client.network.FormaCraftClientNetworking;
import com.formacraft.client.ui.FormaCraftHudOverlay;
import com.formacraft.client.ui.UiTheme;
import com.formacraft.client.ui.widget.HudClickSupport;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.text.Text;

/** UI helpers extracted from BuildConfirmPanel (behavior-preserving). */
final class BuildConfirmUi {
    private BuildConfirmUi() {}

    static boolean clickButton(ButtonWidget button, Click click) {
        return button != null && button.visible && HudClickSupport.click(button, click);
    }

    static void layoutBuildButtons(BuildConfirmPanel p, int screenW, int screenH) {
        int btnW = 60;
        int btnH = 16;
        int spacing = 10;
        int bottomPad = 8;
        int centerX = screenW / 2;

        int confirmX = centerX - btnW - spacing / 2;
        int cancelX = centerX + spacing / 2;

        int btnY = screenH - btnH - bottomPad;
        if (btnY < 16) btnY = 16;

        p.confirmButton.setPosition(confirmX, btnY);
        p.confirmButton.setWidth(btnW);
        p.confirmButton.visible = true;
        p.confirmButton.active = true;

        p.cancelButton.setPosition(cancelX, btnY);
        p.cancelButton.setWidth(btnW);
        p.cancelButton.visible = true;
        p.cancelButton.active = true;

        if (p.applyPatchButton != null) p.applyPatchButton.visible = false;
        if (p.undoPatchButton != null) p.undoPatchButton.visible = false;
        if (p.redoPatchButton != null) p.redoPatchButton.visible = false;
    }

    static void ensureWidgets(BuildConfirmPanel p) {
        if (p.confirmButton != null) return;
        p.confirmButton = ButtonWidget.builder(Text.translatable("formacraft.preview.confirm"), b -> p.confirm())
                .dimensions(0, 0, 120, 16)
                .tooltip(Tooltip.of(Text.translatable("formacraft.preview.confirm.tooltip")))
                .build();
        p.cancelButton = ButtonWidget.builder(Text.translatable("formacraft.preview.cancel"), b -> p.cancel())
                .dimensions(0, 0, 120, 16)
                .tooltip(Tooltip.of(Text.translatable("formacraft.preview.cancel.tooltip")))
                .build();

        p.applyPatchButton = ButtonWidget.builder(Text.translatable("formacraft.preview.patch.apply"), b -> p.applyPatch())
                .dimensions(0, 0, 90, 16)
                .build();
        p.undoPatchButton = ButtonWidget.builder(Text.translatable("formacraft.preview.patch.undo"), b -> FormaCraftClientNetworking.sendPatchUndo())
                .dimensions(0, 0, 70, 16)
                .tooltip(Tooltip.of(Text.translatable("formacraft.preview.patch.undo.tooltip")))
                .build();
        p.redoPatchButton = ButtonWidget.builder(Text.translatable("formacraft.preview.patch.redo"), b -> FormaCraftClientNetworking.sendPatchRedo())
                .dimensions(0, 0, 70, 16)
                .tooltip(Tooltip.of(Text.translatable("formacraft.preview.patch.redo.tooltip")))
                .build();
    }

    static void render(BuildConfirmPanel p, DrawContext context) {
        if (!p.visible || p.client == null || p.mode == BuildConfirmPanel.Mode.BUILD && p.spec == null) return;
        ensureWidgets(p);
        int sw = p.client.getWindow().getScaledWidth(), sh = p.client.getWindow().getScaledHeight();
        int mx = (int) (p.client.mouse.getX() / p.client.getWindow().getScaleFactor());
        int my = (int) (p.client.mouse.getY() / p.client.getWindow().getScaleFactor());
        if (p.mode != BuildConfirmPanel.Mode.PATCH) {
            layoutBuildButtons(p, sw, sh);
            p.confirmButton.render(context, mx, my, 0);
            p.cancelButton.render(context, mx, my, 0);
            drawFirstMatchingHudTooltip(p, context, mx, my, new ClickableWidget[]{p.confirmButton, p.cancelButton}, null);
            return;
        }
        var box = patchBounds(p);
        layoutPatchButtons(p, box);
        context.fill(box.x(), box.y(), box.x() + box.width(), box.y() + box.height(), 0xD9000000);
        UiTheme.drawPanelBorder(context, box.x(), box.y(), box.x() + box.width(), box.y() + box.height());
        context.drawCenteredTextWithShadow(p.client.textRenderer, Text.translatable("formacraft.preview.patch.title"), box.x() + box.width() / 2, box.y() + 7, 0xFFFFFFFF);
        int place = 0, remove = 0, replace = 0;
        if (p.patchList != null) for (var patch : p.patchList) if (patch != null && patch.action() != null) switch (patch.action().toLowerCase(java.util.Locale.ROOT)) {
            case "place" -> place++;
            case "remove" -> remove++;
            case "replace" -> replace++;
        }
        var lines = new java.util.ArrayList<Text>();
        lines.add(Text.translatable("formacraft.preview.patch.drag_hint"));
        if (p.patchOrigin != null) lines.add(Text.translatable("formacraft.preview.patch.origin", p.patchOrigin.getX(), p.patchOrigin.getY(), p.patchOrigin.getZ()));
        lines.add(Text.translatable("formacraft.preview.patch.summary", place, remove, replace, p.rejectedPatchList == null ? 0 : p.rejectedPatchList.size()));
        if (p.awaitingPatchApplyResult) lines.add(Text.translatable("formacraft.preview.patch.applying"));
        if (p.patchWarnings != null && !p.patchWarnings.isEmpty()) lines.add(Text.literal(p.patchWarnings.getFirst()));
        int y = box.y() + 24, limit = box.buttonY(0) - 4;
        context.enableScissor(box.x() + 4, box.y() + 22, box.x() + box.width() - 4, limit);
        for (Text line : lines) for (var wrapped : p.client.textRenderer.wrapLines(line, box.width() - 16)) {
            if (y + p.client.textRenderer.fontHeight > limit) break;
            context.drawTextWithShadow(p.client.textRenderer, wrapped, box.x() + 8, y, 0xFFCCCCCC);
            y += p.client.textRenderer.fontHeight + 1;
        }
        context.disableScissor();
        for (var button : new ButtonWidget[]{p.applyPatchButton, p.undoPatchButton, p.redoPatchButton, p.cancelButton}) button.render(context, mx, my, 0);
        drawFirstMatchingHudTooltip(p, context, mx, my, new ClickableWidget[]{p.applyPatchButton, p.undoPatchButton, p.redoPatchButton, p.cancelButton}, null);
        if (box.contains(mx, my) && my >= box.y() + 22 && my < box.buttonY(0) && p.patchWarnings != null && !p.patchWarnings.isEmpty())
            UiTheme.drawTooltip(context, p.client, p.patchWarnings.stream().filter(java.util.Objects::nonNull).map(Text::literal).map(t -> (Text) t).toList(), mx, my);
    }

    static PatchPanelPlacement.Bounds patchBounds(BuildConfirmPanel p) {
        BasePanel sidebar = switch (FormaCraftHudOverlay.activePanel) {
            case CHAT -> FormaCraftHudOverlay.CHAT_PANEL;
            case TOOLS -> FormaCraftHudOverlay.TOOL_PANEL;
            case SETTINGS -> FormaCraftHudOverlay.SETTINGS_PANEL;
            case COMPONENT_LIBRARY -> FormaCraftHudOverlay.COMPONENT_LIBRARY_PANEL;
            case COMPONENT_CAPTURE -> FormaCraftHudOverlay.COMPONENT_CAPTURE_PANEL;
            default -> null;
        };
        int right = 0;
        if (sidebar != null) {
            sidebar.ensureLayout();
            right = sidebar.getPanelX() + sidebar.getPanelWidth();
        }
        return p.patchPlacement.layout(p.client.getWindow().getScaledWidth(), p.client.getWindow().getScaledHeight(), right);
    }

    static void layoutPatchButtons(BuildConfirmPanel p, PatchPanelPlacement.Bounds box) {
        var buttons = new ButtonWidget[]{p.applyPatchButton, p.undoPatchButton, p.redoPatchButton, p.cancelButton};
        for (int i = 0; i < buttons.length; i++) {
            buttons[i].setPosition(box.buttonX(i), box.buttonY(i));
            buttons[i].setWidth(box.buttonWidth());
            buttons[i].visible = true;
        }
        p.applyPatchButton.active = !p.awaitingPatchApplyResult && p.previewTicketId != null;
        p.undoPatchButton.active = p.patchCanUndo;
        p.redoPatchButton.active = p.patchCanRedo;
        p.cancelButton.active = true;
    }

    static void drawFirstMatchingHudTooltip(BuildConfirmPanel p, DrawContext ctx, int mouseX, int mouseY,
                                            ClickableWidget[] widgets, Text[] tooltips) {
        if (p.client.currentScreen != null || widgets == null) {
            return;
        }
        for (int i = 0; i < widgets.length; i++) {
            ClickableWidget widget = widgets[i];
            if (widget == null || !widget.visible || !widget.isMouseOver(mouseX, mouseY)) {
                continue;
            }
            Text tip = (tooltips != null && i < tooltips.length && tooltips[i] != null)
                    ? tooltips[i]
                    : widget.getMessage();
            if (tip == null) {
                continue;
            }
            UiTheme.drawTooltip(ctx, p.client, java.util.Collections.singletonList(tip), mouseX, mouseY);
            return;
        }
    }

    static boolean mouseClicked(BuildConfirmPanel p, double mouseX, double mouseY, int button) {
        if (!p.visible || p.client == null) return false;
        ensureWidgets(p);
        if (p.mode != BuildConfirmPanel.Mode.PATCH) {
            if (button != 0) return true;
            layoutBuildButtons(p, p.client.getWindow().getScaledWidth(), p.client.getWindow().getScaledHeight());
            var click = new Click(mouseX, mouseY, new MouseInput(button, 0));
            if (!clickButton(p.confirmButton, click)) clickButton(p.cancelButton, click);
            return true;
        }
        var box = patchBounds(p);
        if (!box.contains(mouseX, mouseY)) return false;
        if (button != 0) return true;
        if (box.title(mouseX, mouseY)) {
            p.draggingPatch = true;
            p.patchDragX = mouseX - box.x();
            p.patchDragY = mouseY - box.y();
            return true;
        }
        layoutPatchButtons(p, box);
        var click = new Click(mouseX, mouseY, new MouseInput(button, 0));
        for (var widget : new ButtonWidget[]{p.applyPatchButton, p.undoPatchButton, p.redoPatchButton, p.cancelButton})
            if (clickButton(widget, click)) break;
        return true;
    }

    static void runPreviewCommand(BuildConfirmPanel p, String commandNoSlash) {
        if (p.client == null) return;
        Object nh = p.client.getNetworkHandler();
        if (nh == null) return;
        String cmd = (commandNoSlash == null) ? "" : commandNoSlash.trim();
        if (cmd.isEmpty()) return;

        try {
            java.lang.reflect.Method m = nh.getClass().getMethod("sendChatCommand", String.class);
            m.invoke(nh, cmd);
            return;
        } catch (Throwable t) {
            BuildConfirmPanel.LOG.warn("sendChatCommand failed cmd={}", cmd, t);
        }

        try {
            java.lang.reflect.Method m2 = nh.getClass().getMethod("sendChatMessage", String.class);
            m2.invoke(nh, "/" + cmd);
        } catch (Throwable t) {
            BuildConfirmPanel.LOG.warn("sendChatMessage fallback failed cmd={}", cmd, t);
        }
    }
}
