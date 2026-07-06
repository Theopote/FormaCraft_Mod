package com.formacraft.server.generation.structure;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.server.generation.typology.builder.DenseEavesPagodaBuilder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Legacy landmark entry point — delegates to {@link DenseEavesPagodaBuilder} (square footprint).
 * @deprecated Prefer {@code STRUCTURE + typology:dense_eaves_pagoda} with {@code footprint=square}.
 */
@Deprecated
public class GiantWildGoosePagodaGenerator implements StructureGenerator {

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        return DenseEavesPagodaBuilder.fromBuildingSpec(spec, origin, world);
    }
}
