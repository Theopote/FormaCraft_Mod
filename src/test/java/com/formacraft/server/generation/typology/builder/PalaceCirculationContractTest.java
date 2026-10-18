package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round 54: circulation contract from actual Builder emit — floors, wings, flights.
 */
class PalaceCirculationContractTest {

    private static final BlockPos ORIGIN = new BlockPos(0, 1, 0);

    @BeforeAll
    static void init() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void createPreviewPlanCarriesActualFloorAnchorsNotRequestedTiersAlone() {
        PalacePreviewPlan plan = potalaPlan();
        assertNotNull(plan.circulation(), "Builder must emit CirculationContract");
        assertTrue(plan.circulation().hasAnchors());
        assertEquals(plan.circulation().actualTier(), plan.circulation().floors().size());
        // Anchors come from emitted tiers — floorY matches preferredAnchor.y
        for (var floor : plan.circulation().floors()) {
            assertEquals(floor.floorY(), floor.preferredAnchor().y());
        }
        assertFalse(plan.validateFinalVoxelCirculation().isEmpty()
                        && !plan.circulation().hasAnchors(),
                "sanity");
        assertTrue(plan.validateFinalVoxelCirculation().isEmpty(),
                "intact potala plan must pass shared circulation validator: "
                        + plan.validateFinalVoxelCirculation());
    }

    @Test
    void wingBlockageRejectedWhenPotala() {
        PalacePreviewPlan plan = potalaPlan();
        assertNotNull(plan.circulation());
        assertTrue(plan.circulation().potala(),
                "reference_landmark=potala_palace must set potala on contract");
        assertFalse(plan.circulation().wings().isEmpty(),
                "potala emit must include wing anchors; wings=" + plan.circulation().wings().size());

        var wing = plan.circulation().wings().getFirst();
        List<BlockPatch> merged = new ArrayList<>(plan.patches());
        // Seal a volume around the wing anchor with solid blocks (body clearance).
        for (int dy = 0; dy <= 3; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    merged.add(new BlockPatch(
                            BlockPatch.PLACE,
                            wing.relativeAnchor().x() + dx,
                            wing.relativeAnchor().y() + dy,
                            wing.relativeAnchor().z() + dz,
                            "minecraft:stone_bricks"));
                }
            }
        }
        // Seal may overwrite wing link clearance and/or break wing reachability —
        // either class of diagnostic is a valid refuse.
        assertNull(plan.resolveForAcceptedPatches(merged),
                "blocking potala wing must refuse merge");
        PalacePreviewPlan trial = plan.freezeMerged(merged);
        assertNotNull(trial);
        var issues = trial.validateMergedIntegrity(plan);
        assertFalse(issues.isEmpty(), "expected integrity diagnostics, got none");
        assertTrue(issues.stream().anyMatch(d ->
                        d.code().startsWith("WING_")
                                || d.code().startsWith("CLEARANCE_")
                                || d.code().startsWith("FLOOR_")
                                || d.code().startsWith("FLIGHT_")),
                "blocking potala wing must yield structural diagnostic: " + issues);
    }

    @Test
    void topFloorBlockageRejected() {
        PalacePreviewPlan plan = potalaPlan();
        var floors = plan.circulation().floors();
        assertTrue(floors.size() >= 2);
        var top = floors.get(floors.size() - 1);
        List<BlockPatch> merged = new ArrayList<>(plan.patches());
        for (int dy = 0; dy <= 3; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    merged.add(new BlockPatch(
                            BlockPatch.PLACE,
                            top.preferredAnchor().x() + dx,
                            top.floorY() + dy,
                            top.preferredAnchor().z() + dz,
                            "minecraft:stone_bricks"));
                }
            }
        }
        assertNull(plan.resolveForAcceptedPatches(merged),
                "sealing top floor must refuse merge");
    }

    @Test
    void stairLayoutCorruptionRejectedEvenIfCountPreserved() {
        PalacePreviewPlan plan = potalaPlan();
        assertFalse(plan.circulation().flights().isEmpty());
        var flight = plan.circulation().flights().getFirst();
        assertFalse(flight.treadCells().isEmpty());

        // Move every tread one cell sideways — count of stairs may stay similar if we also
        // place stairs at new cells, but contract tread cells are overwritten with dirt.
        List<BlockPatch> merged = new ArrayList<>();
        java.util.HashSet<Long> treadKeys = new java.util.HashSet<>();
        for (var t : flight.treadCells()) {
            treadKeys.add(pack(t.x(), t.y(), t.z()));
        }
        for (BlockPatch p : plan.patches()) {
            long key = pack(p.dx(), p.dy(), p.dz());
            if (treadKeys.contains(key) && PalacePreviewPlan.isStairPatch(p)) {
                merged.add(new BlockPatch(BlockPatch.PLACE, p.dx(), p.dy(), p.dz(), "minecraft:dirt"));
                // Compensate count with a stair elsewhere (wrong layout).
                merged.add(new BlockPatch(
                        BlockPatch.PLACE, p.dx() + 5, p.dy(), p.dz() + 5,
                        "minecraft:stone_brick_stairs"));
            } else {
                merged.add(p);
            }
        }
        assertNull(plan.resolveForAcceptedPatches(merged),
                "wrong stair layout must refuse even if stair count similar");
    }

    @Test
    void decorativeMergeStillAllowed() {
        PalacePreviewPlan plan = potalaPlan();
        List<BlockPatch> merged = new ArrayList<>(plan.patches());
        merged.add(new BlockPatch(BlockPatch.PLACE, 120, 1, 120, "minecraft:oak_planks"));
        PalacePreviewPlan resolved = plan.resolveForAcceptedPatches(merged);
        assertNotNull(resolved);
        assertNotNull(resolved.circulation());
        assertEquals(plan.circulation().actualTier(), resolved.circulation().actualTier());
    }

    @Test
    void largePalaceUsesAdaptiveBudgetNotFixed2048Failure() {
        Map<String, Object> extras = baseExtras();
        extras.put("baseWidth", 49);
        extras.put("baseDepth", 41);
        extras.put("tiers", 5);
        extras.put("reference_landmark", "potala_palace");
        BuildingSpec spec = new BuildingSpec();
        spec.setExtra(new HashMap<>(extras));
        var result = TieredMountainPalaceBuilder.generateDetailed(extras, ORIGIN, null, spec);
        assertTrue(result.hasGeometry(), "large schematic should emit geometry");
        PalacePreviewPlan plan = TieredMountainPalaceBuilder.createPreviewPlan(result, ORIGIN, null, spec);
        // Force SUCCESS wrapper with contract for merge validation.
        PalacePreviewPlan exec = new PalacePreviewPlan(
                TieredMountainPalaceBuilder.GenerateStatus.SUCCESS,
                plan.patches(), plan.context(), plan.diagnostics(),
                plan.paramsSnapshot(), plan.paletteId(),
                plan.corridorMx(), plan.pathStartZ(), plan.padY(), plan.maxDrop(),
                plan.circulation());
        assertNotNull(exec.circulation());
        var issues = exec.validateFinalVoxelCirculation();
        assertTrue(issues.stream().noneMatch(d -> d.code().contains("SEARCH_LIMIT")),
                "adaptive budget must not spuriously SEARCH_LIMIT: " + issues);
    }

    private static PalacePreviewPlan potalaPlan() {
        Map<String, Object> extras = baseExtras();
        extras.put("reference_landmark", "potala_palace");
        BuildingSpec spec = new BuildingSpec();
        spec.setExtra(new HashMap<>(extras));
        var result = TieredMountainPalaceBuilder.generateDetailed(extras, ORIGIN, null, spec);
        PalacePreviewPlan schematic =
                TieredMountainPalaceBuilder.createPreviewPlan(result, ORIGIN, null, spec);
        return new PalacePreviewPlan(
                TieredMountainPalaceBuilder.GenerateStatus.SUCCESS,
                schematic.patches(), schematic.context(), schematic.diagnostics(),
                schematic.paramsSnapshot(), schematic.paletteId(),
                schematic.corridorMx(), schematic.pathStartZ(), schematic.padY(), schematic.maxDrop(),
                schematic.circulation());
    }

    private static Map<String, Object> baseExtras() {
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

    private static long pack(int dx, int dy, int dz) {
        return (((long) dx & 0x1FFFFFL) << 42)
                | (((long) dy & 0x1FFFFFL) << 21)
                | ((long) dz & 0x1FFFFFL);
    }
}
