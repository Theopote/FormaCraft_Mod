package com.formacraft.common.item;

import com.formacraft.client.event.InputEventHandler;
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
        if (!world.isClient()) {
            return ActionResult.SUCCESS;
        }
        InputEventHandler.openChatScreen();
        return ActionResult.SUCCESS;
    }
}