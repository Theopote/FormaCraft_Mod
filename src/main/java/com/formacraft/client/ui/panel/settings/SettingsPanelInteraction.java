package com.formacraft.client.ui.panel.settings;

import com.formacraft.client.ui.panel.SettingsPanel;
import com.formacraft.client.ui.widget.HudTextInput;
import com.formacraft.config.SettingsConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.*;

/** Draw / input / config helpers carved from SettingsPanel (move-only). */
public final class SettingsPanelInteraction {
    private SettingsPanelInteraction() {}

    public static void drawContents(SettingsPanel host, DrawContext ctx) {
        host.ensureWidgets();
        host.probes.syncConnectionIdentity();
        host.probes.syncSearchIdentity();
        int x = host.getPanelX() + CONTENT_PADDING;
        int w = host.getPanelWidth() - CONTENT_PADDING * 2;
        int contentTop = host.getContentY() + CONTENT_PADDING;
        int footerY = host.getPanelY() + host.getPanelHeight() - SettingsActionsSection.HEIGHT - CONTENT_PADDING;
        int clipBottom = Math.max(contentTop + 1, footerY - 4);
        host.ui.begin(host.getContentY() + 1, clipBottom);
        host.drawContentBackgroundPublic(ctx);
        int y = contentTop - host.scrollY;
        ctx.enableScissor(host.getPanelX() + 1, host.getContentY() + 1, host.getPanelX() + host.getPanelWidth() - 1, clipBottom);
        try {
            for (var group : SettingsGroup.values()) {
                ButtonWidget header = host.groupButtons.get(group);
                header.setMessage(Text.literal((host.collapsed.contains(group) ? "▶ " : "▼ ") + group.label));
                host.ui.widget(ctx, host.client, header, x, y, w, BUTTON_HEIGHT);
                y += BUTTON_ROW_HEIGHT;
                if (host.collapsed.contains(group)) continue;
                y = switch (group) {
                    case CONNECTION -> SettingsConnectionSection.drawSection(host, ctx, x, y, w);
                    case SEARCH -> SettingsSearchSection.drawSection(host, ctx, x, y, w);
                    case PREFERENCES -> SettingsPreferencesSection.drawSection(host, ctx, x, y, w);
                    case BACKEND -> host.backendSection.draw(ctx, x, y, w);
                };
                y += GROUP_GAP;
            }
        } finally {
            ctx.disableScissor();
        }
        host.maxScrollY = Math.max(0, y + host.scrollY - contentTop - (clipBottom - contentTop));
        host.scrollY = Math.max(0, Math.min(host.scrollY, host.maxScrollY));
        ctx.fill(host.getPanelX() + 1, footerY - 3, host.getPanelX() + host.getPanelWidth() - 1, host.getPanelY() + host.getPanelHeight() - 1, 0xFF171B24);
        int statusY = SettingsActionsSection.drawSection(host, ctx, x, footerY, w);
        String message = host.toast == null ? "修改后保存；取消撤销草稿" : host.toast;
        host.statusButton.setMessage(Text.literal(host.client.textRenderer.trimToWidth((host.isToastError ? "错误：" : "") + message + " [详情]", w - 8)));
        host.ui.widget(ctx, host.client, host.statusButton, x, statusY, w, BUTTON_HEIGHT);
        host.ui.fixed(false);
        host.ui.finish();
        host.choices.render(ctx, host.client, host.getPanelX() + host.getPanelWidth(), host.getContentY());
        host.details.render(ctx, host.client, host.getPanelX() + host.getPanelWidth(), host.getContentY());
    }

    public static boolean mouseClicked(SettingsPanel host, double mouseX, double mouseY, int button) {
        if (host.details.isOpen()) {
            host.details.click(mouseX, mouseY, button);
            return true;
        }
        if (host.choices.isOpen()) {
            host.choices.click(mouseX, mouseY, button);
            return true;
        }
        if (host.superMouseClicked(mouseX, mouseY, button)) return true;
        return SettingsInputController.handleClick(host, mouseX, mouseY, button);
    }

    public static boolean mouseDragged(SettingsPanel host, double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        return SettingsInputController.handleDrag(host, mouseX, mouseY, button, deltaX, deltaY);
    }

    public static boolean mouseReleased(SettingsPanel host, double mouseX, double mouseY, int button) {
        return SettingsInputController.handleRelease(host, mouseX, mouseY, button);
    }

    public static void mouseScrolled(SettingsPanel host, double mouseX, double mouseY, double amount) {
        if (host.details.isOpen()) {
            host.details.scroll(amount);
            return;
        }
        if (host.choices.isOpen()) {
            host.choices.scroll(amount);
            return;
        }
        SettingsInputController.handleScroll(host, mouseX, mouseY, amount);
    }

    public static boolean drawCustomTooltip(SettingsPanel host, DrawContext ctx, double mouseX, double mouseY) {
        if (host.details.isOpen()) return true;
        String tip = host.choices.isOpen() ? host.choices.hoveredLabel(mouseX, mouseY) : host.ui.tooltip(mouseX, mouseY);
        if (tip == null) return false;
        host.drawTooltipCompatPublic(ctx, List.of(Text.literal(tip)), (int) mouseX, (int) mouseY);
        return true;
    }

    public static void afterTextEdit(SettingsPanel host, HudTextInput input, String before) {
        if (before.equals(input.getText())) return;
        if (input == host.modelInput) {
            host.draftModel = input.getText().trim();
            host.modelNeedsConfirmation = false;
        }
        if (input == host.reachInput || input == host.temperatureInput) {
            try {
                var values = SettingsPreferenceValues.parse(host.reachInput.getText(), host.temperatureInput.getText());
                host.interactionReachSlider.setCustomValue(reachToValue(values.reach()));
                host.temperatureSlider.setCustomValue(values.temperature());
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public static void loadFromConfig(SettingsPanel host) {
        host.widgets.blurInputs();
        host.choices.close();
        host.details.close();
        host.searchRevision++;
        host.validatingSearchKey = false;
        host.connectionRevision++;
        SettingsConfigCoordinator.load(host);
        host.connectionIdentity = host.probes.currentConnectionIdentity();
        host.searchIdentity = host.probes.currentSearchIdentity();
        host.detectingModel = false;
        host.validatingKey = false;
        if (host.testKeyButton != null) host.testKeyButton.setMessage(Text.literal("校验 Key"));
        host.availableModels = new ArrayList<>();
        host.availableModelsUpdatedAtMs = 0L;
        host.modelNeedsConfirmation = false;
    }

    public static boolean saveSettings(SettingsPanel host) {
        host.probes.syncConnectionIdentity();
        if (host.modelNeedsConfirmation) {
            host.showToast("连接已更改，请确认或重新填写 Model 后再保存", true);
            host.widgets.openModels();
            return false;
        }
        boolean saved = SettingsConfigCoordinator.save(host);
        if (saved) {
            host.connectionRevision++;
            host.connectionIdentity = host.probes.currentConnectionIdentity();
            host.detectingModel = false;
        }
        return saved;
    }

    public static void charTyped(SettingsPanel host, char chr) {
        host.probes.syncConnectionIdentity();
        host.probes.syncSearchIdentity();
        if (host.details.isOpen()) return;
        if (host.choices.isOpen()) {
            host.choices.charTyped(chr);
            return;
        }
        var focused = host.ui.focused();
        if (focused == null) return;
        String before = focused.getText();
        focused.charTyped(chr);
        afterTextEdit(host, focused, before);
    }

    public static void keyPressed(SettingsPanel host, int keyCode) {
        keyPressed(host, keyCode, 0, 0);
    }

    public static void keyPressed(SettingsPanel host, int keyCode, int scanCode, int modifiers) {
        host.probes.syncConnectionIdentity();
        host.probes.syncSearchIdentity();
        if (host.details.isOpen()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) host.details.close();
            return;
        }
        if (host.choices.isOpen()) {
            host.choices.keyPressed(keyCode, modifiers);
            return;
        }
        var focused = host.ui.focused();
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            if (focused == null) return;
            host.setScrollY(Math.max(0, Math.min(host.maxScrollY, host.scrollY + host.ui.tab((modifiers & GLFW.GLFW_MOD_SHIFT) != 0))));
            return;
        }
        if (focused != null) {
            String before = focused.getText();
            focused.keyPressed(keyCode, modifiers);
            afterTextEdit(host, focused, before);
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER) saveSettings(host);
    }

    public static boolean wantsKeyboardInput(SettingsPanel host) {
        return host.choices.isOpen() || host.details.isOpen() || host.ui.focused() != null;
    }

    public static void blurKeyboardFocus(SettingsPanel host) {
        host.widgets.blurInputs();
        if (host.choices.isOpen()) host.choices.blur();
    }

    public static float clamp01(float v) {
        if (v < 0.0f) return 0.0f;
        return Math.min(v, 1.0f);
    }

    public static int clampReach(int v) {
        if (v < MIN_INTERACTION_REACH) return MIN_INTERACTION_REACH;
        return Math.min(v, MAX_INTERACTION_REACH);
    }

    public static double reachToValue(int reach) {
        int clamped = Math.max(MIN_INTERACTION_REACH, Math.min(MAX_INTERACTION_REACH, reach));
        return (clamped - MIN_INTERACTION_REACH) / (double) (MAX_INTERACTION_REACH - MIN_INTERACTION_REACH);
    }

    public static int valueToReach(double value) {
        double v = Math.max(0.0, Math.min(1.0, value));
        return MIN_INTERACTION_REACH + (int) Math.round(v * (MAX_INTERACTION_REACH - MIN_INTERACTION_REACH));
    }
}
