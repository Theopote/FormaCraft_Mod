package com.formacraft.client.ui.panel.settings;

import net.minecraft.client.gui.DrawContext;
import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.*;

/** Fixed footer; configuration controls never scroll away. */
public final class SettingsActionsSection {
    public static final int HEIGHT=60;
    private SettingsActionsSection() {}
    public static int drawSection(SettingsPanelRenderHost host,DrawContext ctx,int x,int y,int w) {
        var ui=host.ui(); var client=host.client(); int half=(w-BUTTON_GAP)/2;
        ui.fixed(true);
        ui.widget(ctx,client,host.saveButton(),x,y,half,BUTTON_HEIGHT);
        ui.widget(ctx,client,host.cancelButton(),x+half+BUTTON_GAP,y,w-half-BUTTON_GAP,BUTTON_HEIGHT); y+=BUTTON_ROW_HEIGHT;
        ui.widget(ctx,client,host.resetButton(),x,y,w,BUTTON_HEIGHT); y+=BUTTON_ROW_HEIGHT;
        return y;
    }
}
