package com.formacraft.common.item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

/**
 * FormaCraft 塑形笔：出现在实用工具创造栏，右键打开建造面板。
 */
public class FormaCraftToolItem extends Item {

    public FormaCraftToolItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        return ActionResult.SUCCESS;
    }
}
