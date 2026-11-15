package com.formacraft.client.ui.panel.settings;

import com.formacraft.client.ui.widget.HudClickSupport;
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.MouseInput;

/** Input dispatch shares the rendered controls and their clipping bounds. */
public final class SettingsInputController {
    private SettingsInputController() {}
    public static boolean handleClick(SettingsPanelRenderHost host,double x,double y,int button) {
        if (!host.isMouseOver(x,y)) { host.ui().blur(); HudClickSupport.clearPending(); return false; }
        return host.ui().click(x,y,button);
    }
    public static boolean handleDrag(SettingsPanelRenderHost host,double x,double y,int button,double dx,double dy) {
        return button == 0 && host.ui().drag(x);
    }
    public static boolean handleRelease(SettingsPanelRenderHost host,double x,double y,int button) {
        if (button != 0) return false;
        host.ui().release();
        return HudClickSupport.release(new Click(x,y,new MouseInput(button,0)));
    }
    public static void handleScroll(SettingsPanelRenderHost host,double x,double y,double amount) {
        if (!host.isMouseOver(x,y) || host.ui().scrollInput(x,y,amount)) return;
        host.setScrollY(Math.max(0,Math.min(host.maxScrollY(),host.scrollY()-(int)Math.signum(amount)*18)));
    }
}
