package com.formacraft.server.generation.structure;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.server.generation.typology.builder.DenseEavesPagodaBuilder;
import com.formacraft.server.generation.typology.builder.RadialTerraceHallBuilder;
import com.formacraft.server.generation.typology.builder.TailiangTimberHallBuilder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * Whole-structure generator backed by native typology builders (Phase 4).
 */
public final class TypologyBackedStructureGenerator implements StructureGenerator {

    private final String typologyId;

    public TypologyBackedStructureGenerator(String typologyId) {
        this.typologyId = typologyId;
    }

    public String typologyId() {
        return typologyId;
    }

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        if (typologyId == null || typologyId.isBlank()) {
            return null;
        }
        return switch (typologyId.trim().toLowerCase()) {
            case DenseEavesPagodaBuilder.TYPOLOGY_ID ->
                    DenseEavesPagodaBuilder.fromBuildingSpec(spec, origin, world);
            case TailiangTimberHallBuilder.TYPOLOGY_ID ->
                    TailiangTimberHallBuilder.fromBuildingSpec(spec, origin, world);
            case RadialTerraceHallBuilder.TYPOLOGY_ID ->
                    RadialTerraceHallBuilder.fromBuildingSpec(spec, origin, world);
            default -> null;
        };
    }
}
