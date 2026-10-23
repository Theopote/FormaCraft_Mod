package com.formacraft.client.item;

import com.formacraft.FormacraftMod;
import com.formacraft.client.ui.FormacraftUIState;
import com.formacraft.client.ui.FormaCraftHudOverlay;
import com.formacraft.client.ui.panel.PanelType;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.util.ActionResult;

/** UI behavior is registered exclusively by the client initializer. */
public final class FormaCraftToolInteraction {
    private FormaCraftToolInteraction() {}

    public static void register() {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient() && player.getStackInHand(hand).isOf(FormacraftMod.FORMACRAFT_TOOL)) {
                FormacraftUIState.toggle();
                if (FormacraftUIState.isOpen) {
                    FormaCraftHudOverlay.activePanel = PanelType.CHAT;
                }
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });
    }
}
