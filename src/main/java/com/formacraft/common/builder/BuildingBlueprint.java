package com.formacraft.common.builder;

import com.formacraft.common.lang.StructureData;
import net.minecraft.util.math.BlockPos;

public class BuildingBlueprint {
    private final StructureData data;
    private final BlockPos origin;

    public BuildingBlueprint(StructureData data, BlockPos origin) {
        this.data = data;
        this.origin = origin;
    }

    public StructureData getData() {
        return data;
    }

    public BlockPos getOrigin() {
        return origin;
    }
}
