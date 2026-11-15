package com.formacraft.client.ui.panel.settings;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.*;

/** LLM / backend connection fields in the settings panel. */
public final class SettingsConnectionSection {
    private SettingsConnectionSection() {}

    public static int drawSection(SettingsPanelRenderHost host, DrawContext ctx, int x, int y, int w) {
        var client = host.client();
        host.ensureWidgets();

        host.ui().label(ctx, client, (Text.translatable("formacraft.settings.backend_url")).getString(), x, y, w, COLOR_GRAY);
        y += LABEL_OFFSET;
        host.ui().input(ctx, host.orchestratorInput(), x, y, w, INPUT_HEIGHT);
        y += FIELD_SPACING;

        host.ui().label(ctx, client, (Text.translatable("formacraft.settings.api_key")).getString(), x, y, w, COLOR_GRAY);
        int inputY = y + LABEL_OFFSET;
        host.apiKeyInput().setPasswordMode(host.hideKey());
        host.ui().input(ctx, host.apiKeyInput(), x, inputY, w, INPUT_HEIGHT);
        int btnY = y + LABEL_OFFSET * 2;
        int gap = BUTTON_GAP_SMALL;
        int btnW = Math.max(0, (w - gap * 2) / 3);
        int btnW3 = Math.max(0, w - btnW * 2 - gap * 2);
        host.showHideButton().setPosition(x, btnY);
        host.showHideButton().setWidth(btnW);
        host.showHideButton().visible = true;
        host.showHideButton().active = true;
        host.ui().widget(ctx, client, host.showHideButton(), host.showHideButton().getX(), host.showHideButton().getY(), host.showHideButton().getWidth(), BUTTON_HEIGHT);
        host.pasteButton().setPosition(x + btnW + gap, btnY);
        host.pasteButton().setWidth(btnW);
        host.pasteButton().visible = true;
        host.pasteButton().active = true;
        host.ui().widget(ctx, client, host.pasteButton(), host.pasteButton().getX(), host.pasteButton().getY(), host.pasteButton().getWidth(), BUTTON_HEIGHT);
        host.testKeyButton().setPosition(x + btnW * 2 + gap * 2, btnY);
        host.testKeyButton().setWidth(btnW3);
        host.testKeyButton().visible = true;
        host.testKeyButton().active = true;
        host.ui().widget(ctx, client, host.testKeyButton(), host.testKeyButton().getX(), host.testKeyButton().getY(), host.testKeyButton().getWidth(), BUTTON_HEIGHT);
        y = afterThreeRowField(y);

        host.ui().label(ctx, client, (Text.literal("LLM Provider")).getString(), x, y, w, COLOR_GRAY);
        y += LABEL_OFFSET;
        host.llmProviderButton().setMessage(host.llmProviderButtonText());
        host.llmProviderButton().setPosition(x, y);
        host.llmProviderButton().setWidth(w);
        host.llmProviderButton().visible = true;
        host.llmProviderButton().active = true;
        host.ui().widget(ctx, client, host.llmProviderButton(), host.llmProviderButton().getX(), host.llmProviderButton().getY(), host.llmProviderButton().getWidth(), BUTTON_HEIGHT);
        y += FIELD_SPACING;

        y = drawBaseUrlField(host, ctx, x, y, w);
        y = nextLabelAfterRow(y);

        y = drawModelField(host, ctx, x, y, w);
        return nextLabelAfterRow(y);
    }

    private static int drawModelField(SettingsPanelRenderHost host, DrawContext ctx, int x, int y, int w) {
        var client = host.client();
        host.ui().label(ctx, client, (host.modelNeedsConfirmation() ? Text.literal("Model（需确认）") : Text.translatable("formacraft.settings.model")).getString(), x, y, w, COLOR_GRAY);
        y += LABEL_OFFSET;
        host.ui().input(ctx, host.modelInput(), x, y, w, INPUT_HEIGHT);
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
        host.ui().widget(ctx, client, host.detectModelButton(), host.detectModelButton().getX(), host.detectModelButton().getY(), host.detectModelButton().getWidth(), BUTTON_HEIGHT);
        host.modelChoicesButton().setPosition(x + btnW1 + gap, y);
        host.modelChoicesButton().setWidth(btnW2);
        host.modelChoicesButton().visible = true;
        host.modelChoicesButton().active = true;
        host.ui().widget(ctx, client, host.modelChoicesButton(), host.modelChoicesButton().getX(), host.modelChoicesButton().getY(), host.modelChoicesButton().getWidth(), BUTTON_HEIGHT);
        return y;
    }

    private static int drawBaseUrlField(SettingsPanelRenderHost host, DrawContext ctx, int x, int y, int w) {
        var client = host.client();
        host.ui().label(ctx, client, (Text.literal("LLM Base URL")).getString(), x, y, w, COLOR_GRAY);
        y += LABEL_OFFSET;
        host.llmBaseUrlPresetButton().setMessage(host.baseUrlPresetButtonText());
        host.llmBaseUrlPresetButton().setPosition(x, y);
        host.llmBaseUrlPresetButton().setWidth(w);
        host.llmBaseUrlPresetButton().visible = true;
        host.llmBaseUrlPresetButton().active = true;
        host.ui().widget(ctx, client, host.llmBaseUrlPresetButton(), host.llmBaseUrlPresetButton().getX(), host.llmBaseUrlPresetButton().getY(), host.llmBaseUrlPresetButton().getWidth(), BUTTON_HEIGHT);
        y += LABEL_OFFSET;
        host.ui().input(ctx, host.llmBaseUrlInput(), x, y, w, INPUT_HEIGHT);
        return y;
    }

}
