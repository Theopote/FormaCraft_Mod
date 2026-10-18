package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round 54–55: circulation contract from actual Builder emit —
 * region-bounded floors/wings, per-flight continuity, strict BlockState.
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
        for (var floor : plan.circulation().floors()) {
            assertEquals(floor.floorY(), floor.preferredAnchor().y());
            assertNotNull(floor.interior());
            assertTrue(floor.interior().contains(floor.preferredAnchor().x(), floor.floorY(),
                            floor.preferredAnchor().z()),
                    "preferred anchor must lie inside floor interior region");
        }
        assertTrue(plan.validateFinalVoxelCirculation().isEmpty(),
                "intact potala plan must pass shared circulation validator: "
                        + plan.validateFinalVoxelCirculation());
    }

    @Test
    void wingBlockageRejectedWhenPotala() {
        PalacePreviewPlan plan = potalaPlan();
        assertNotNull(plan.circulation());
        assertTrue(plan.circulation().potala());
        assertFalse(plan.circulation().wings().isEmpty());

        var wing = plan.circulation().wings().getFirst();
        List<BlockPatch> merged = new ArrayList<>(plan.patches());
        // Seal the wing interior volume only (not link mouth / exterior terrace).
        for (int y = wing.interior().minY(); y <= wing.interior().maxY() + 2; y++) {
            for (int x = wing.interior().minX(); x <= wing.interior().maxX(); x++) {
                for (int z = wing.interior().minZ(); z <= wing.interior().maxZ(); z++) {
                    merged.add(new BlockPatch(
                            BlockPatch.PLACE, x, y, z, "minecraft:stone_bricks"));
                }
            }
        }
        assertNull(plan.resolveForAcceptedPatches(merged),
                "blocking potala wing interior must refuse merge");
        PalacePreviewPlan trial = plan.freezeMerged(merged);
        assertNotNull(trial);
        var issues = trial.validateMergedIntegrity(plan);
        assertTrue(issues.stream().anyMatch(d ->
                        d.code().startsWith("WING_")
                                || d.code().startsWith("CLEARANCE_")
                                || d.code().startsWith("FLOOR_")
                                || d.code().startsWith("FLIGHT_")),
                "blocking potala wing must yield structural diagnostic: " + issues);
    }

    @Test
    void wingTerraceReachableButInteriorSealedRejected() {
        PalacePreviewPlan plan = potalaPlan();
        var wing = plan.circulation().wings().getFirst();
        List<BlockPatch> merged = new ArrayList<>(plan.patches());
        // Fill only interior floor slab cells so no standable point remains inside region,
        // while roof/terrace cells outside the region stay untouched.
        for (int x = wing.interior().minX(); x <= wing.interior().maxX(); x++) {
            for (int z = wing.interior().minZ(); z <= wing.interior().maxZ(); z++) {
                merged.add(new BlockPatch(
                        BlockPatch.PLACE, x, wing.relativeAnchor().y() + 1, z,
                        "minecraft:stone_bricks"));
                merged.add(new BlockPatch(
                        BlockPatch.PLACE, x, wing.relativeAnchor().y() + 2, z,
                        "minecraft:stone_bricks"));
            }
        }
        assertNull(plan.resolveForAcceptedPatches(merged),
                "wing terrace must not substitute for sealed wing interior");
        PalacePreviewPlan trial = plan.freezeMerged(merged);
        var issues = trial.validateMergedIntegrity(plan);
        assertTrue(issues.stream().anyMatch(d ->
                        d.code().equals("WING_NO_STAND") || d.code().equals("WING_UNREACHABLE")
                                || d.code().startsWith("CLEARANCE_")),
                "expected WING_* for sealed interior: " + issues);
    }

    @Test
    void topFloorBlockageRejected() {
        PalacePreviewPlan plan = potalaPlan();
        var floors = plan.circulation().floors();
        assertTrue(floors.size() >= 2);
        var top = floors.get(floors.size() - 1);
        List<BlockPatch> merged = new ArrayList<>(plan.patches());
        for (int x = top.interior().minX(); x <= top.interior().maxX(); x++) {
            for (int z = top.interior().minZ(); z <= top.interior().maxZ(); z++) {
                for (int dy = 1; dy <= 3; dy++) {
                    merged.add(new BlockPatch(
                            BlockPatch.PLACE, x, top.floorY() + dy, z,
                            "minecraft:stone_bricks"));
                }
            }
        }
        assertNull(plan.resolveForAcceptedPatches(merged),
                "sealing top floor interior must refuse merge");
    }

    @Test
    void stairLayoutCorruptionRejectedEvenIfCountPreserved() {
        PalacePreviewPlan plan = potalaPlan();
        assertFalse(plan.circulation().flights().isEmpty());
        var flight = plan.circulation().flights().getFirst();
        assertFalse(flight.treadCells().isEmpty());

        List<BlockPatch> merged = new ArrayList<>();
        Set<Long> treadKeys = new HashSet<>();
        for (var t : flight.treadCells()) {
            treadKeys.add(pack(t.x(), t.y(), t.z()));
        }
        for (BlockPatch p : plan.patches()) {
            long key = pack(p.dx(), p.dy(), p.dz());
            if (treadKeys.contains(key) && PalacePreviewPlan.isStairPatch(p)) {
                merged.add(new BlockPatch(BlockPatch.PLACE, p.dx(), p.dy(), p.dz(), "minecraft:dirt"));
                merged.add(new BlockPatch(
                        BlockPatch.PLACE, p.dx() + 5, p.dy(), p.dz() + 5,
                        "minecraft:stone_brick_stairs[facing=south,half=bottom]"));
            } else {
                merged.add(p);
            }
        }
        assertNull(plan.resolveForAcceptedPatches(merged),
                "wrong stair layout must refuse even if stair count similar");
    }

    @Test
    void damagedFlightRejectedEvenWhenAnotherFlightBypasses() {
        PalacePreviewPlan plan = potalaPlan();
        assertTrue(plan.circulation().flights().size() >= 1);
        var flight = plan.circulation().flights().getFirst();
        assertTrue(flight.treadCells().size() >= 2);

        // Destroy only the middle of this flight — leave other flights intact.
        List<BlockPatch> merged = new ArrayList<>();
        Set<Long> kill = new HashSet<>();
        int mid = flight.treadCells().size() / 2;
        var tread = flight.treadCells().get(mid);
        kill.add(pack(tread.x(), tread.y(), tread.z()));
        for (BlockPatch p : plan.patches()) {
            long key = pack(p.dx(), p.dy(), p.dz());
            if (kill.contains(key)) {
                merged.add(new BlockPatch(BlockPatch.PLACE, p.dx(), p.dy(), p.dz(),
                        "minecraft:stone_bricks"));
            } else {
                merged.add(p);
            }
        }
        PalacePreviewPlan trial = plan.freezeMerged(merged);
        assertNotNull(trial);
        var issues = trial.validateFinalVoxelCirculation();
        assertTrue(issues.stream().anyMatch(d ->
                        d.code().startsWith("FLIGHT_") || d.code().startsWith("BLOCKSTATE_")),
                "damaged flight must fail even if another path may connect floors: " + issues);
        assertNull(plan.resolveForAcceptedPatches(merged));
    }

    @Test
    void stairFacingCorruptionRejected() {
        PalacePreviewPlan plan = potalaPlan();
        var flight = plan.circulation().flights().getFirst();
        Direction expected = flight.treadFacings().getFirst();
        Direction wrong = expected.getOpposite();
        Vec3i tread = flight.treadCells().getFirst();

        List<BlockPatch> merged = new ArrayList<>();
        long key = pack(tread.x(), tread.y(), tread.z());
        for (BlockPatch p : plan.patches()) {
            if (pack(p.dx(), p.dy(), p.dz()) == key) {
                merged.add(new BlockPatch(
                        BlockPatch.PLACE, p.dx(), p.dy(), p.dz(),
                        "minecraft:stone_brick_stairs[facing="
                                + wrong.asString() + ",half=bottom]"));
            } else {
                merged.add(p);
            }
        }
        var issues = plan.freezeMerged(merged).validateFinalVoxelCirculation();
        assertTrue(issues.stream().anyMatch(d -> d.code().equals("FLIGHT_TREAD_FACING")),
                "wrong tread facing must yield FLIGHT_TREAD_FACING: " + issues);
    }

    @Test
    void doorHalfCorruptionRejected() {
        PalacePreviewPlan plan = potalaPlan();
        var door = plan.circulation().lowerDoorRel();
        List<BlockPatch> merged = new ArrayList<>();
        long key = pack(door.x(), door.y(), door.z());
        for (BlockPatch p : plan.patches()) {
            if (pack(p.dx(), p.dy(), p.dz()) == key) {
                merged.add(new BlockPatch(
                        BlockPatch.PLACE, p.dx(), p.dy(), p.dz(),
                        "minecraft:dark_oak_door[facing=south,half=upper,hinge=left,open=false,powered=false]"));
            } else {
                merged.add(p);
            }
        }
        var issues = plan.freezeMerged(merged).validateFinalVoxelCirculation();
        assertTrue(issues.stream().anyMatch(d ->
                        d.code().equals("DOOR_HALF") || d.code().equals("DOOR_STATE")),
                "wrong door half must be rejected: " + issues);
        assertNull(plan.resolveForAcceptedPatches(merged));
    }

    @Test
    void invalidBlockStateRejected() {
        PalacePreviewPlan plan = potalaPlan();
        var flight = plan.circulation().flights().getFirst();
        Vec3i tread = flight.treadCells().getFirst();
        List<BlockPatch> merged = new ArrayList<>();
        long key = pack(tread.x(), tread.y(), tread.z());
        for (BlockPatch p : plan.patches()) {
            if (pack(p.dx(), p.dy(), p.dz()) == key) {
                merged.add(new BlockPatch(
                        BlockPatch.PLACE, p.dx(), p.dy(), p.dz(),
                        "minecraft:stone_brick_stairs[facing=north,half=bogus]"));
            } else {
                merged.add(p);
            }
        }
        var issues = plan.freezeMerged(merged).validateFinalVoxelCirculation();
        assertTrue(issues.stream().anyMatch(d -> d.code().equals("BLOCKSTATE_INVALID")),
                "illegal stair props must yield BLOCKSTATE_INVALID: " + issues);
        // String contains("stairs") must NOT make it pass as a stair under strict mode.
        BlockPatch bogus = new BlockPatch(
                BlockPatch.PLACE, 0, 0, 0,
                "minecraft:stone_brick_stairs[facing=north,half=bogus]");
        assertFalse(PalaceCirculationValidator.isStairPatch(bogus));
        assertTrue(PalaceCirculationValidator.looksLikeStairHint(bogus));
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
