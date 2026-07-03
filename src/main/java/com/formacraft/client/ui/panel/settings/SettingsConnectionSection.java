package com.formacraft.client.ui.panel.settings;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import static com.formacraft.client.ui.panel.settings.SettingsPanelDrawSupport.drawSmallLabel;
import static com.formacraft.client.ui.panel.settings.SettingsPanelDrawSupport.scaledMouseX;
import static com.formacraft.client.ui.panel.settings.SettingsPanelDrawSupport.scaledMouseY;
import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.*;

/** LLM / backend connection fields in the settings panel. */
public final class SettingsConnectionSection {
    private static final int BUTTON_GAP_SMALL = 4;

    private SettingsConnectionSection() {}

    public static int drawSection(SettingsPanelRenderHost host, DrawContext ctx, int x, int y, int w) {
        var client = host.client();
        host.ensureWidgets();

        drawSmallLabel(client, ctx, Text.translatable("formacraft.settings.backend_url"), x, y);
        y += LABEL_OFFSET;
        host.orchestratorInput().render(ctx, x, y, w, INPUT_HEIGHT);
        y += FIELD_SPACING;

        drawSmallLabel(client, ctx, Text.translatable("formacraft.settings.api_key"), x, y);
        int inputY = y + LABEL_OFFSET;
        host.apiKeyInput().setPasswordMode(host.hideKey() && !host.apiKeyInput().isFocused());
        host.apiKeyInput().render(ctx, x, inputY, w, INPUT_HEIGHT);
        int btnY = y + LABEL_OFFSET * 2;
        int gap = BUTTON_GAP_SMALL;
        int btnW1 = (w - gap) / 2;
        int btnW2 = Math.max(0, w - gap - btnW1);
        host.showHideButton().setPosition(x, btnY);
        host.showHideButton().setWidth(btnW1);
        host.showHideButton().visible = true;
        host.showHideButton().active = true;
        host.showHideButton().render(ctx, (int) scaledMouseX(client), (int) scaledMouseY(client), 0.0f);
        host.pasteButton().setPosition(x + btnW1 + gap, btnY);
        host.pasteButton().setWidth(btnW2);
        host.pasteButton().visible = true;
        host.pasteButton().active = true;
        host.pasteButton().render(ctx, (int) scaledMouseX(client), (int) scaledMouseY(client), 0.0f);
        y += FIELD_SPACING + LABEL_OFFSET;

        drawSmallLabel(client, ctx, Text.literal("LLM Provider"), x, y);
        y += LABEL_OFFSET;
        host.llmProviderButton().setMessage(host.llmProviderButtonText());
        host.llmProviderButton().setPosition(x, y);
        host.llmProviderButton().setWidth(w);
        host.llmProviderButton().visible = true;
        host.llmProviderButton().active = true;
        host.llmProviderButton().render(ctx, (int) scaledMouseX(client), (int) scaledMouseY(client), 0.0f);
        y += FIELD_SPACING;

        y = drawBaseUrlField(host, ctx, x, y, w);
        // BaseURL 是三行区块，drawBaseUrlField 返回第三行起点；
        // 这里只需补一行偏移进入下一组 label，避免出现过大空隙。
        y += LABEL_OFFSET;

        y = drawModelField(host, ctx, x, y, w);
        // Model 也是三行区块，结束后仅补一行偏移进入下一 section。
        return y + LABEL_OFFSET;
    }

    public static void renderOverlays(SettingsPanelRenderHost host, DrawContext ctx) {
        renderModelDropdownOverlay(host, ctx);
        renderBaseUrlDropdownOverlay(host, ctx);
    }

    private static int drawModelField(SettingsPanelRenderHost host, DrawContext ctx, int x, int y, int w) {
        var client = host.client();
        drawSmallLabel(client, ctx, Text.translatable("formacraft.settings.model"), x, y);
        y += LABEL_OFFSET;
        host.modelInput().render(ctx, x, y, w, INPUT_HEIGHT);
        if (!host.modelInput().isFocused()) {
            String t = host.modelInput().getText() == null ? "" : host.modelInput().getText().trim();
            if (t.isEmpty()) {
                ctx.drawTextWithShadow(client.textRenderer, Text.literal("自动（留空）"), x + 4, y + 4, 0xFF777777);
            }
        }
        y += LABEL_OFFSET;
        int gap = BUTTON_GAP_SMALL;
        int btnW1 = (w - gap) / 2;
        int btnW2 = Math.max(0, w - gap - btnW1);
        host.detectModelButton().setMessage(host.detectModelButtonText());
        host.detectModelButton().setPosition(x, y);
        host.detectModelButton().setWidth(btnW1);
        host.detectModelButton().visible = true;
        host.detectModelButton().active = !host.detectingModel();
        host.detectModelButton().render(ctx, (int) scaledMouseX(client), (int) scaledMouseY(client), 0.0f);
        host.autoModelButton().setPosition(x + btnW1 + gap, y);
        host.autoModelButton().setWidth(btnW2);
        host.autoModelButton().visible = true;
        host.autoModelButton().active = true;
        host.autoModelButton().render(ctx, (int) scaledMouseX(client), (int) scaledMouseY(client), 0.0f);
        if (host.modelDropdownOpen()) {
            host.setPendingModelDropdownOverlay(true, x, y - LABEL_OFFSET + INPUT_HEIGHT, w);
        } else {
            host.hideModelOptionButtons();
        }
        return y;
    }

    private static int drawBaseUrlField(SettingsPanelRenderHost host, DrawContext ctx, int x, int y, int w) {
        var client = host.client();
        drawSmallLabel(client, ctx, Text.literal("LLM Base URL"), x, y);
        y += LABEL_OFFSET;
        host.llmBaseUrlPresetButton().setMessage(host.baseUrlPresetButtonText());
        host.llmBaseUrlPresetButton().setPosition(x, y);
        host.llmBaseUrlPresetButton().setWidth(w);
        host.llmBaseUrlPresetButton().visible = true;
        host.llmBaseUrlPresetButton().active = true;
        host.llmBaseUrlPresetButton().render(ctx, (int) scaledMouseX(client), (int) scaledMouseY(client), 0.0f);
        y += LABEL_OFFSET;
        SettingsBaseUrlPresets.Preset preset = host.selectedBaseUrlPreset();
        if (preset != null && preset.url() == null) {
            host.llmBaseUrlInput().render(ctx, x, y, w, INPUT_HEIGHT);
        } else {
            if (preset == null || preset.url().isBlank()) {
                ctx.drawTextWithShadow(client.textRenderer, Text.literal("自动：由 Provider/后端决定"), x, y + 4, COLOR_GRAY);
            }
            host.llmBaseUrlInput().setFocused(false);
        }
        if (host.baseUrlPresetDropdownOpen()) {
            int presetBtnTopY = y - LABEL_OFFSET;
            host.setPendingBaseUrlDropdownOverlay(true, x, presetBtnTopY + BUTTON_HEIGHT, w);
        } else {
            host.hideBaseUrlPresetButtons();
        }
        return y;
    }

    private static void renderModelDropdownOverlay(SettingsPanelRenderHost host, DrawContext ctx) {
        if (!host.modelDropdownOpen()) {
            host.hideModelOptionButtons();
            return;
        }
        if (!host.pendingModelDropdownOverlay()) {
            host.hideModelOptionButtons();
            return;
        }
        host.layoutModelOptionButtons(host.pendingModelDropdownX(), host.pendingModelDropdownY(), host.pendingModelDropdownW());
        var client = host.client();
        for (ButtonWidget b : host.modelOptionButtons()) {
            if (b.visible) {
                b.render(ctx, (int) scaledMouseX(client), (int) scaledMouseY(client), 0.0f);
            }
        }
    }

    private static void renderBaseUrlDropdownOverlay(SettingsPanelRenderHost host, DrawContext ctx) {
        if (!host.baseUrlPresetDropdownOpen()) {
            host.hideBaseUrlPresetButtons();
            return;
        }
        if (!host.pendingBaseUrlDropdownOverlay()) {
            host.hideBaseUrlPresetButtons();
            return;
        }
        host.layoutBaseUrlPresetButtons(host.pendingBaseUrlDropdownX(), host.pendingBaseUrlDropdownY(), host.pendingBaseUrlDropdownW());
        var client = host.client();
        for (ButtonWidget b : host.llmBaseUrlPresetOptionButtons()) {
            if (b.visible) {
                b.render(ctx, (int) scaledMouseX(client), (int) scaledMouseY(client), 0.0f);
            }
        }
    }
}
