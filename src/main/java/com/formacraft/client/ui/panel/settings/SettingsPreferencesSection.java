package com.formacraft.client.ui.panel.settings;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import static com.formacraft.client.ui.panel.settings.SettingsPanelDrawSupport.drawSmallLabel;
import static com.formacraft.client.ui.panel.settings.SettingsPanelDrawSupport.scaledMouseX;
import static com.formacraft.client.ui.panel.settings.SettingsPanelDrawSupport.scaledMouseY;
import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.*;

/** UI / gameplay preference fields in the settings panel. */
public final class SettingsPreferencesSection {
    private SettingsPreferencesSection() {}

    public static int drawSection(SettingsPanelRenderHost host, DrawContext ctx, int x, int y, int w) {
        var client = host.client();
        host.ensureWidgets();

        drawSmallLabel(client, ctx, Text.literal("Debug Warnings"), x, y);
        y += LABEL_OFFSET;
        host.debugWarningsButton().setMessage(host.debugWarningsButtonText());
        host.debugWarningsButton().setPosition(x, y);
        host.debugWarningsButton().setWidth(w);
        host.debugWarningsButton().visible = true;
        host.debugWarningsButton().active = true;
        host.debugWarningsButton().render(ctx, (int) scaledMouseX(client), (int) scaledMouseY(client), 0.0f);
        y += FIELD_SPACING;

        drawSmallLabel(client, ctx, Text.translatable("formacraft.settings.interaction_reach", host.draftInteractionReach()), x, y);
        y += LABEL_OFFSET;
        host.interactionReachSlider().setPosition(x, y);
        host.interactionReachSlider().setWidth(w);
        host.interactionReachSlider().visible = true;
        host.interactionReachSlider().active = true;
        host.interactionReachSlider().render(ctx, (int) scaledMouseX(client), (int) scaledMouseY(client), 0.0f);
        y += FIELD_SPACING;

        String tempText = host.cachedTemperatureText();
        drawSmallLabel(client, ctx, Text.translatable("formacraft.settings.temperature", tempText), x, y);
        y += LABEL_OFFSET;
        host.temperatureSlider().setPosition(x, y);
        host.temperatureSlider().setWidth(w);
        host.temperatureSlider().visible = true;
        host.temperatureSlider().active = true;
        host.temperatureSlider().render(ctx, (int) scaledMouseX(client), (int) scaledMouseY(client), 0.0f);
        y += FIELD_SPACING;

        drawSmallLabel(client, ctx, Text.translatable("formacraft.settings.font_size", host.draftFontSize()), x, y);
        y += LABEL_OFFSET;
        host.fontSizeSlider().setPosition(x, y);
        host.fontSizeSlider().setWidth(w);
        host.fontSizeSlider().visible = true;
        host.fontSizeSlider().active = true;
        host.fontSizeSlider().render(ctx, (int) scaledMouseX(client), (int) scaledMouseY(client), 0.0f);
        return y + FIELD_SPACING;
    }
}
