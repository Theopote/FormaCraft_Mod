package com.formacraft.server.generation.structure;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.server.generation.typology.builder.TailiangTimberHallBuilder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Legacy landmark entry point — delegates to {@link TailiangTimberHallBuilder}.
 * @deprecated Prefer {@code STRUCTURE + typology:tailiang_timber_hall}.
 */
@Deprecated
public class FoguangTempleHallGenerator implements StructureGenerator {

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        return TailiangTimberHallBuilder.fromBuildingSpec(spec, origin, world);
    }
}
