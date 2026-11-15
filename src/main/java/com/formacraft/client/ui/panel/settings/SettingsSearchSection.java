package com.formacraft.client.ui.panel.settings;

import net.minecraft.client.gui.DrawContext;
import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.*;

public final class SettingsSearchSection {
    private SettingsSearchSection() {}
    public static int drawSection(SettingsPanelRenderHost host, DrawContext ctx, int x, int y, int w) {
        var ui=host.ui(); var client=host.client();
        host.searchProviderButton().setMessage(host.searchProviderButtonText());
        ui.widget(ctx,client,host.searchProviderButton(),x,y,w,BUTTON_HEIGHT); y+=FIELD_SPACING;
        String provider=host.draftSearchProvider();
        if (SettingsSearchProviders.showsCredentials(provider)) {
            ui.label(ctx,client,"Search API Key",x,y,w,COLOR_GRAY); y+=LABEL_OFFSET;
            host.searchApiKeyInput().setPlaceholder("留空使用后端环境变量");
            host.searchApiKeyInput().setPasswordMode(host.hideKey());
            ui.input(ctx,host.searchApiKeyInput(),x,y,w,INPUT_HEIGHT); y+=FIELD_SPACING;
        }
        if (SettingsSearchProviders.showsGoogleCx(provider)) {
            ui.label(ctx,client,"Google 搜索引擎 ID",x,y,w,COLOR_GRAY); y+=LABEL_OFFSET;
            host.googleCseCxInput().setPlaceholder("留空使用后端环境变量");
            ui.input(ctx,host.googleCseCxInput(),x,y,w,INPUT_HEIGHT); y+=FIELD_SPACING;
        }
        host.testSearchKeyButton().setMessage(host.testSearchKeyButtonText());
        host.testSearchKeyButton().active=!host.validatingSearchKey();
        ui.widget(ctx,client,host.testSearchKeyButton(),x,y,w,BUTTON_HEIGHT);
        return y+FIELD_SPACING;
    }
}
