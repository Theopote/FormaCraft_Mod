package com.formacraft.client.ui.panel.settings;

import net.minecraft.client.gui.DrawContext;
import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.*;

public final class SettingsPreferencesSection {
    private SettingsPreferencesSection() {}
    public static int drawSection(SettingsPanelRenderHost host, DrawContext ctx, int x, int y, int w) {
        var ui=host.ui(); var client=host.client();
        host.debugWarningsButton().setMessage(host.debugWarningsButtonText());
        ui.widget(ctx,client,host.debugWarningsButton(),x,y,w,BUTTON_HEIGHT); y+=FIELD_SPACING;
        int fieldWidth=46, sliderWidth=Math.max(16,w-fieldWidth-BUTTON_GAP);
        ui.label(ctx,client,"交互距离（5–100 格）",x,y,w,COLOR_GRAY); y+=LABEL_OFFSET;
        ui.widget(ctx,client,host.interactionReachSlider(),x,y,sliderWidth,INPUT_HEIGHT);
        ui.input(ctx,host.reachInput(),x+sliderWidth+BUTTON_GAP,y,fieldWidth,INPUT_HEIGHT); y+=FIELD_SPACING;
        ui.label(ctx,client,"模型温度（0–1）",x,y,w,COLOR_GRAY); y+=LABEL_OFFSET;
        ui.widget(ctx,client,host.temperatureSlider(),x,y,sliderWidth,INPUT_HEIGHT);
        ui.input(ctx,host.temperatureInput(),x+sliderWidth+BUTTON_GAP,y,fieldWidth,INPUT_HEIGHT); y+=FIELD_SPACING;
        String error=host.preferenceError();
        if (error != null) { ui.label(ctx,client,error,x,y,w,0xFFFF8888); y+=LABEL_OFFSET; }
        return y;
    }
}
