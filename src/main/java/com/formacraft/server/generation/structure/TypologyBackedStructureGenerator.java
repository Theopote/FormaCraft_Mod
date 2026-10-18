package com.formacraft.server.generation.structure;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.typology.TypologyGenerationException;
import com.formacraft.server.generation.typology.builder.DenseEavesPagodaBuilder;
import com.formacraft.server.generation.typology.builder.GothicCathedralHallBuilder;
import com.formacraft.server.generation.typology.builder.RadialTerraceHallBuilder;
import com.formacraft.server.generation.typology.builder.StadiumBowlBuilder;
import com.formacraft.server.generation.typology.builder.SuspensionBridgeBuilder;
import com.formacraft.server.generation.typology.builder.TailiangTimberHallBuilder;
import com.formacraft.server.generation.typology.builder.TieredMountainPalaceBuilder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;

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
            case StadiumBowlBuilder.TYPOLOGY_ID ->
                    StadiumBowlBuilder.fromBuildingSpec(spec, origin, world);
            case SuspensionBridgeBuilder.TYPOLOGY_ID ->
                    SuspensionBridgeBuilder.fromBuildingSpec(spec, origin, world);
            case GothicCathedralHallBuilder.TYPOLOGY_ID ->
                    GothicCathedralHallBuilder.fromBuildingSpec(spec, origin, world);
            case TieredMountainPalaceBuilder.TYPOLOGY_ID -> palaceOrThrow(spec, origin, world);
            default -> null;
        };
    }

    private static GeneratedStructure palaceOrThrow(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        TieredMountainPalaceBuilder.PalaceGenerateResult result =
                TieredMountainPalaceBuilder.fromBuildingSpecDetailed(spec, origin, world);
        List<String> diags = result.diagnostics().stream()
                .map(TieredMountainPalaceBuilder.BuildDiagnostic::toString)
                .toList();
        if (result.failed()) {
            throw new TypologyGenerationException(
                    TieredMountainPalaceBuilder.TYPOLOGY_ID,
                    "CIRCULATION_FAILED",
                    "tiered_mountain_palace structure generation failed: " + String.join("; ", diags),
                    diags
            );
        }
        if (!result.executable()) {
            throw new TypologyGenerationException(
                    TieredMountainPalaceBuilder.TYPOLOGY_ID,
                    "SCHEMATIC_PREVIEW_NOT_EXECUTABLE",
                    "schematic palace preview cannot be applied as a whole-structure build",
                    diags
            );
        }
        return result.structure();
    }
}
