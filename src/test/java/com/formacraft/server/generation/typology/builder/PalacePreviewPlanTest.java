package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.BuildExecutionStatus;
import com.formacraft.common.patch.WorldPatchTransaction;
import com.formacraft.common.typology.TypologyGenerationException;
import com.formacraft.test.FakeBlockMutationAccess;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PalacePreviewPlanTest {

    private static final BlockPos ORIGIN = new BlockPos(0, 1, 0);

    @BeforeAll
    static void init() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void createPreviewPlanFreezesPatchesAndContext() {
        Map<String, Object> extras = schematicExtras();
        BuildingSpec spec = specOf(extras);
        var result = TieredMountainPalaceBuilder.generateDetailed(extras, ORIGIN, null, spec);
        assertEquals(TieredMountainPalaceBuilder.GenerateStatus.SCHEMATIC_ONLY, result.status());
        assertFalse(result.buildContextHash().isBlank());
        assertFalse(result.terrainContentHash().isBlank());

        PalacePreviewPlan plan = TieredMountainPalaceBuilder.createPreviewPlan(result, ORIGIN, null, spec);
        assertEquals(result.buildContextHash(), plan.planId());
        assertEquals(result.buildContextHash(), plan.context().contextHash());
        assertFalse(plan.patches().isEmpty());
        assertFalse(plan.executable()); // schematic
        assertEquals(PalacePreviewPlan.ApplyRejectReason.NOT_EXECUTABLE,
                plan.validateForApply(null, ORIGIN, Direction.SOUTH, extras, null,
                        12, 30, 3, 8, (o, f, x, y, z) -> o.add(x, y, z)).reason());
    }

    @Test
    void applyConfirmedForTestUsesFrozenPatchesWithoutRegen() {
        Map<String, Object> extras = schematicExtras();
        BuildingSpec spec = specOf(extras);
        var result = TieredMountainPalaceBuilder.generateDetailed(extras, ORIGIN, null, spec);
        PalacePreviewPlan fromGenerate =
                TieredMountainPalaceBuilder.createPreviewPlan(result, ORIGIN, null, spec);
        assertFalse(fromGenerate.patches().isEmpty());

        // Hand-sized SUCCESS plan: Apply executes these confirmed patches only.
        List<BlockPatch> frozen = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:stone"),
                new BlockPatch(BlockPatch.PLACE, 1, 1, 0, "minecraft:cobblestone")
        );
        PalacePreviewPlan executable = new PalacePreviewPlan(
                TieredMountainPalaceBuilder.GenerateStatus.SUCCESS,
                frozen,
                fromGenerate.context(),
                fromGenerate.diagnostics());
        assertTrue(executable.executable());
        assertEquals(frozen, executable.patches());

        FakeBlockMutationAccess access = new FakeBlockMutationAccess();
        WorldPatchTransaction.Result applied = executable.applyConfirmedForTest(access, BlockPos.ORIGIN, true);
        assertEquals(BuildExecutionStatus.SUCCESS, applied.status(), applied.summaryZh());
        assertEquals(2, applied.applied());
        assertEquals(2, access.states.size());

        WorldPatchTransaction.Result rejected = executable.applyConfirmedForTest(access, BlockPos.ORIGIN, false);
        assertEquals(BuildExecutionStatus.PREFLIGHT_REJECTED, rejected.status());
    }

    @Test
    void differentOriginsShareTerrainContentButNotBuildContext() {
        Map<String, Object> extras = schematicExtras();
        BuildingSpec spec = specOf(extras);
        BlockPos a = new BlockPos(0, 64, 0);
        BlockPos b = new BlockPos(200, 64, 50);
        var ra = TieredMountainPalaceBuilder.generateDetailed(extras, a, null, spec);
        var rb = TieredMountainPalaceBuilder.generateDetailed(extras, b, null, spec);
        assertEquals(ra.terrainContentHash(), rb.terrainContentHash(),
                "same schematic profile → same terrain content hash");
        assertNotEquals(ra.buildContextHash(), rb.buildContextHash(),
                "different origins must not share build-context identity");
    }

    @Test
    void paramChangeBreaksBuildContextHash() {
        Map<String, Object> extras = schematicExtras();
        BuildingSpec spec = specOf(extras);
        var base = TieredMountainPalaceBuilder.generateDetailed(extras, ORIGIN, null, spec);
        extras.put("tiers", 4);
        var changed = TieredMountainPalaceBuilder.generateDetailed(extras, ORIGIN, null, specOf(extras));
        assertEquals(base.terrainContentHash(), changed.terrainContentHash());
        assertNotEquals(base.buildContextHash(), changed.buildContextHash());
    }

    @Test
    void staleBuildContextHashFailsGenerate() {
        Map<String, Object> extras = schematicExtras();
        BuildingSpec spec = specOf(extras);
        var first = TieredMountainPalaceBuilder.generateDetailed(extras, ORIGIN, null, spec);
        extras.put("buildContextHash", "deadbeefdeadbeef");
        var stale = TieredMountainPalaceBuilder.generateDetailed(extras, ORIGIN, null, specOf(extras));
        assertTrue(stale.failed());
        assertTrue(stale.diagnostics().stream().anyMatch(d -> d.code().equals("CONTEXT_MISMATCH")));
        assertFalse(first.buildContextHash().isBlank());
    }

    @Test
    void createPreviewPlanRejectsDivergentContext() {
        Map<String, Object> extras = schematicExtras();
        BuildingSpec spec = specOf(extras);
        var result = TieredMountainPalaceBuilder.generateDetailed(extras, ORIGIN, null, spec);
        // Tamper result context hash
        var tampered = new TieredMountainPalaceBuilder.PalaceGenerateResult(
                result.status(), result.structure(), result.diagnostics(),
                result.terrainContentHash(), "ffffffffffffffff");
        assertThrows(TypologyGenerationException.class,
                () -> TieredMountainPalaceBuilder.createPreviewPlan(tampered, ORIGIN, null, spec));
    }

    private static Map<String, Object> schematicExtras() {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("baseWidth", 25);
        extras.put("baseDepth", 21);
        extras.put("tiers", 3);
        extras.put("tierInset", 2);
        extras.put("tierHeight", 5);
        extras.put("platformHeight", 3);
        extras.put("facing", "SOUTH");
        extras.put("schematicFlatTerrain", true);
        return extras;
    }

    private static BuildingSpec specOf(Map<String, Object> extras) {
        BuildingSpec spec = new BuildingSpec();
        spec.setExtra(new HashMap<>(extras));
        return spec;
    }
}
