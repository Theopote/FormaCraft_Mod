package com.formacraft.client.item;

import com.formacraft.client.ui.FormacraftUIState;
import com.formacraft.client.ui.FormaCraftHudOverlay;
import com.formacraft.client.ui.panel.PanelType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

public class FormaCraftToolItem extends Item {

    public FormaCraftToolItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        if (world.isClient()) {
            FormacraftUIState.toggle();
            if (FormacraftUIState.isOpen) {
                FormaCraftHudOverlay.activePanel = PanelType.CHAT;
            }
        }
        return ActionResult.SUCCESS;
    }
}
