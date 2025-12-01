package com.formacraft.common.builder;

import com.formacraft.ai.AIResult;
import com.formacraft.ai.BuildingRequest;
import net.minecraft.util.math.BlockPos;

public class BuildingPlanner {

    public BuildingBlueprint plan(BuildingRequest request, AIResult result) {
        if (result == null || result.getStructureData() == null) {
            BlockPos origin = request != null ? request.getOrigin() : BlockPos.ORIGIN;
            return new BuildingBlueprint(null, origin);
        }

        BlockPos origin = request != null ? request.getOrigin() : BlockPos.ORIGIN;
        return new BuildingBlueprint(result.getStructureData(), origin);
    }
}
