package com.formacraft.common.builder;

import net.minecraft.world.World;

public class AutoBuilder {

    public void build(World world, BuildingBlueprint blueprint) {
        if (world == null || blueprint == null || blueprint.getData() == null) return;
        StructureBuilder.generate(world, blueprint.getOrigin(), blueprint.getData());
    }
}
