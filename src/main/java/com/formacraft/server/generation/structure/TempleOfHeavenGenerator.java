package com.formacraft.server.generation.structure;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.server.generation.typology.builder.RadialTerraceHallBuilder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Legacy landmark entry point — delegates to {@link RadialTerraceHallBuilder}.
 * @deprecated Prefer {@code STRUCTURE + typology:radial_terrace_hall}.
 */
@Deprecated
public class TempleOfHeavenGenerator implements StructureGenerator {

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        return RadialTerraceHallBuilder.fromBuildingSpec(spec, origin, world);
    }
}
