package com.formacraft.client.ui.panel.settings;

import com.formacraft.client.ui.widget.HudTextInput;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import static com.formacraft.client.ui.panel.settings.SettingsPanelDrawSupport.drawSmallLabel;
import static com.formacraft.client.ui.panel.settings.SettingsPanelDrawSupport.scaledMouseX;
import static com.formacraft.client.ui.panel.settings.SettingsPanelDrawSupport.scaledMouseY;
import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.*;

/** Web search API settings for building research. */
public final class SettingsSearchSection {
    private SettingsSearchSection() {}

    public static int drawSection(SettingsPanelRenderHost host, DrawContext ctx, int x, int y, int w) {
        var client = host.client();
        host.ensureWidgets();

        drawSmallLabel(client, ctx, Text.literal("搜索引擎（建筑研究）"), x, y);
        y += LABEL_OFFSET;
        host.searchProviderButton().setMessage(host.searchProviderButtonText());
        host.searchProviderButton().setPosition(x, y);
        host.searchProviderButton().setWidth(w);
        host.searchProviderButton().visible = true;
        host.searchProviderButton().active = true;
        host.searchProviderButton().render(ctx, (int) scaledMouseX(client), (int) scaledMouseY(client), 0.0f);
        y += FIELD_SPACING;

        drawSmallLabel(client, ctx, Text.literal("Search API Key（Bing/Google/Tavily/SerpAPI）"), x, y);
        y += LABEL_OFFSET;
        host.searchApiKeyInput().setPasswordMode(host.hideKey() && !host.searchApiKeyInput().isFocused());
        host.searchApiKeyInput().render(ctx, x, y, w, INPUT_HEIGHT);
        if (!host.searchApiKeyInput().isFocused()) {
            String t = host.searchApiKeyInput().getText() == null ? "" : host.searchApiKeyInput().getText().trim();
            if (t.isEmpty()) {
                ctx.drawTextWithShadow(client.textRenderer, Text.literal("留空=使用后端环境变量"), x + 4, y + 4, 0xFF777777);
            }
        }
        y += LABEL_OFFSET;
        host.testSearchKeyButton().setMessage(host.testSearchKeyButtonText());
        host.testSearchKeyButton().setPosition(x, y);
        host.testSearchKeyButton().setWidth(w);
        host.testSearchKeyButton().visible = true;
        host.testSearchKeyButton().active = !host.validatingSearchKey();
        host.testSearchKeyButton().render(ctx, (int) scaledMouseX(client), (int) scaledMouseY(client), 0.0f);
        y += FIELD_SPACING;

        drawSmallLabel(client, ctx, Text.literal("Google CSE CX（搜索引擎 ID）"), x, y);
        y += LABEL_OFFSET;
        host.googleCseCxInput().render(ctx, x, y, w, INPUT_HEIGHT);
        if (!host.googleCseCxInput().isFocused()) {
            String cx = host.googleCseCxInput().getText() == null ? "" : host.googleCseCxInput().getText().trim();
            if (cx.isEmpty()) {
                ctx.drawTextWithShadow(client.textRenderer, Text.literal("Google 搜索时必填"), x + 4, y + 4, 0xFF777777);
            }
        }
        return y + FIELD_SPACING;
    }
}
