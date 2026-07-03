package com.formacraft.client.ui.panel.settings;

import net.minecraft.client.gui.DrawContext;

import static com.formacraft.client.ui.panel.settings.SettingsPanelDrawSupport.scaledMouseX;
import static com.formacraft.client.ui.panel.settings.SettingsPanelDrawSupport.scaledMouseY;
import static com.formacraft.client.ui.panel.settings.SettingsPanelLayout.*;

/** Save / cancel / reset row in the settings panel. */
public final class SettingsActionsSection {
    private SettingsActionsSection() {}

    public static int drawSection(SettingsPanelRenderHost host, DrawContext ctx, int x, int y, int w) {
        var client = host.client();
        host.ensureWidgets();
        int btnW1 = (w - BUTTON_GAP * 2) / 3;
        int btnW3 = Math.max(0, w - (btnW1 + btnW1 + BUTTON_GAP * 2));
        int cancelX = x + btnW1 + BUTTON_GAP;
        int resetX = cancelX + btnW1 + BUTTON_GAP;

        host.saveButton().setPosition(x, y);
        host.saveButton().setWidth(btnW1);
        host.saveButton().visible = true;
        host.saveButton().active = true;
        host.saveButton().render(ctx, (int) scaledMouseX(client), (int) scaledMouseY(client), 0.0f);

        host.cancelButton().setPosition(cancelX, y);
        host.cancelButton().setWidth(btnW1);
        host.cancelButton().visible = true;
        host.cancelButton().active = true;
        host.cancelButton().render(ctx, (int) scaledMouseX(client), (int) scaledMouseY(client), 0.0f);

        host.resetButton().setPosition(resetX, y);
        host.resetButton().setWidth(btnW3);
        host.resetButton().visible = true;
        host.resetButton().active = true;
        host.resetButton().render(ctx, (int) scaledMouseX(client), (int) scaledMouseY(client), 0.0f);
        return y + BUTTON_ROW_HEIGHT;
    }
}
