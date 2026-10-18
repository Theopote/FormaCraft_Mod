package com.formacraft.server.generation.typology.interpreter;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.typology.TypologyGenerationException;
import com.formacraft.common.typology.TypologyParamResolver;
import com.formacraft.server.generation.typology.builder.PalacePreviewPlan;
import com.formacraft.server.generation.typology.builder.TieredMountainPalaceBuilder;
import com.formacraft.server.preview.ConfirmedPlanRegistry;
import com.formacraft.server.preview.GenerationRequestContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Native typology interpreter for {@code tiered_mountain_palace}.
 * Registers a frozen {@link PalacePreviewPlan} under the active
 * {@link GenerationRequestContext} — never by patchHash or planId alone.
 */
public final class TieredMountainPalaceInterpreter implements com.formacraft.common.typology.TypologyInterpreter {

    @Override
    public String typologyId() {
        return TieredMountainPalaceBuilder.TYPOLOGY_ID;
    }

    @Override
    public List<BlockPatch> interpret(SemanticComponent semantic, ServerWorld world) {
        if (semantic == null || world == null || semantic.source() == null) {
            return List.of();
        }
        BlockPos origin = com.formacraft.common.typology.TypologyPatchBridge.worldBuildOrigin(semantic);
        if (origin == null) {
            return List.of();
        }
        Map<String, Object> params = TypologyParamResolver.merge(typologyId(), semantic.source());
        params.remove("schematicFlatTerrain");
        if ("schematic_flat".equalsIgnoreCase(String.valueOf(params.getOrDefault("terrainMode", "")))) {
            params.remove("terrainMode");
        }
        BuildingSpec spec = new BuildingSpec();
        spec.setExtra(new HashMap<>(params));
        TieredMountainPalaceBuilder.PalaceGenerateResult result =
                TieredMountainPalaceBuilder.generateDetailed(params, origin, world, spec);
        List<String> diags = result.diagnostics().stream()
                .map(TieredMountainPalaceBuilder.BuildDiagnostic::toString)
                .toList();
        if (result.failed()) {
            throw new TypologyGenerationException(
                    typologyId(),
                    "CIRCULATION_FAILED",
                    "tiered_mountain_palace generation failed: " + String.join("; ", diags),
                    diags
            );
        }
        if (!result.executable()) {
            throw new TypologyGenerationException(
                    typologyId(),
                    "SCHEMATIC_PREVIEW_NOT_EXECUTABLE",
                    "schematic flat preview cannot be applied to a real world",
                    diags
            );
        }
        PalacePreviewPlan plan = TieredMountainPalaceBuilder.createPreviewPlan(result, origin, world, spec);
        String worldKey = PalacePreviewPlan.worldKeyOf(world);
        GenerationRequestContext.Scope scope = GenerationRequestContext.current();
        if (scope != null) {
            ConfirmedPlanRegistry.putPending(
                    scope.requestId(),
                    scope.playerId(),
                    worldKey,
                    origin,
                    plan);
        } else {
            // No active request scope (unit tests / schematic): bind by ephemeral requestId only.
            ConfirmedPlanRegistry.putPending(
                    UUID.randomUUID(),
                    null,
                    worldKey,
                    origin,
                    plan);
        }
        return plan.patches();
    }
}
