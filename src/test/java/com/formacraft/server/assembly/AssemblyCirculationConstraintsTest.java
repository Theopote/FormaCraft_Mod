package com.formacraft.server.assembly;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.*;
import net.minecraft.util.math.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AssemblyCirculationConstraintsTest {
    @BeforeAll static void initialize() { MinecraftRegistryTestBootstrap.initialize(); }
    private final AssemblyCirculationOps.Adapter adapter = new AssemblyCirculationOps.Adapter() {
        public void put(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin, int x, int y, int z, BlockState state) {
            out.add(new PlannedBlock(PlacementUtil.local(origin, ctx.entranceFacing(), x, y, z), PlacementUtil.rotateState(state, ctx.entranceFacing())));
        }
        public BlockState pick(MetaAssemblyEngine.Context ctx, Map<?, ?> op, String key, String semantic, long salt, BlockState fallback) { return fallback; }
        public int i(Object v, int fallback) { return v instanceof Number n ? n.intValue() : fallback; }
        public boolean bool(Object v, boolean fallback) { return v instanceof Boolean b ? b : fallback; }
        public int clamp(int v, int min, int max) { return Math.max(min, Math.min(v, max)); }
    };
    private Map<String, Object> point(int x, int y, int z) { return Map.of("x", x, "y", y, "z", z); }
    private AssemblyCirculationConstraints.Flight flight(List<PlannedBlock> out, int x, int y, int z, int endX, int endY, int endZ) {
        int start = out.size();
        AssemblyCirculationOps.applyStairSystem(out, new MetaAssemblyEngine.Context(null, BlockPos.ORIGIN, Direction.SOUTH, null), BlockPos.ORIGIN,
            Map.of("from", point(x, y, z), "to", point(endX, endY, endZ), "width", 1), adapter);
        return AssemblyCirculationConstraints.capture(out, start);
    }
    @Test void separatedFlightsAndExplicitFlatLandingAreAccepted() {
        var out = new ArrayList<PlannedBlock>();
        var first = flight(out, 0, 0, 0, 4, 4, 0);
        var landing = flight(out, 5, 4, 0, 5, 4, 2);
        var next = flight(out, 5, 4, 3, 1, 8, 3);
        assertDoesNotThrow(() -> AssemblyCirculationConstraints.validate(out, List.of(first, landing, next)));
    }
    @Test void upperFlightBlockingLowerHeadroomIsRejected() {
        var out = new ArrayList<PlannedBlock>();
        var lower = flight(out, 0, 0, 0, 4, 4, 0);
        var upper = flight(out, 0, 2, 0, 4, 6, 0);
        var error = assertThrows(AssemblyCirculationConstraints.Conflict.class,
            () -> AssemblyCirculationConstraints.validate(out, List.of(lower, upper)));
        assertTrue(error.getMessage().contains("clearance blocked"));
    }
    @Test void laterLowerCarveRemovingUpperTreadsIsRejected() {
        var out = new ArrayList<PlannedBlock>();
        var upper = flight(out, 0, 2, 0, 4, 6, 0);
        var lower = flight(out, 0, 0, 0, 4, 4, 0);
        var error = assertThrows(AssemblyCirculationConstraints.Conflict.class,
            () -> AssemblyCirculationConstraints.validate(out, List.of(upper, lower)));
        assertTrue(error.getMessage().contains("tread/support removed"));
    }
    @Test void laterFloorOrRemoveCannotSilentlyBreakFlight() {
        for (var state : List.of(Blocks.STONE.getDefaultState(), Blocks.AIR.getDefaultState())) {
            var out = new ArrayList<PlannedBlock>(); var flight = flight(out, 0, 0, 0, 4, 4, 0);
            var pos = state.isAir() ? new BlockPos(2, 2, 0) : new BlockPos(2, 4, 0);
            out.add(new PlannedBlock(pos, state));
            assertThrows(AssemblyCirculationConstraints.Conflict.class, () -> AssemblyCirculationConstraints.validate(out, List.of(flight)));
        }
    }
    @Test void transientOverridesUseFinalStateAndUnrelatedPositionsAreIgnored() {
        var out = new ArrayList<PlannedBlock>(); var flight = flight(out, 0, 0, 0, 4, 4, 0);
        var pos = new BlockPos(2, 4, 0);
        out.add(new PlannedBlock(pos, Blocks.STONE.getDefaultState()));
        out.add(new PlannedBlock(pos, Blocks.AIR.getDefaultState()));
        out.add(new PlannedBlock(new BlockPos(20, 4, 20), Blocks.STONE.getDefaultState()));
        assertDoesNotThrow(() -> AssemblyCirculationConstraints.validate(out, List.of(flight)));
    }
    @Test void capturedCoordinatesFollowWorldOriginAndRotation() {
        var out = new ArrayList<PlannedBlock>(); var origin = new BlockPos(-30, 64, 20);
        AssemblyCirculationOps.applyStairSystem(out, new MetaAssemblyEngine.Context(null, origin, Direction.WEST, null), origin,
            Map.of("from", point(0, 0, 0), "to", point(4, 4, 0), "width", 1), adapter);
        var flight = AssemblyCirculationConstraints.capture(out, 0);
        var pos = PlacementUtil.local(origin, Direction.WEST, 2, 4, 0);
        assertTrue(flight.clearance().contains(pos));
        out.add(new PlannedBlock(pos, Blocks.STONE.getDefaultState()));
        assertThrows(AssemblyCirculationConstraints.Conflict.class, () -> AssemblyCirculationConstraints.validate(out, List.of(flight)));
    }
    @Test void disabledCarveAndSupportDoNotCreateExtraPromises() {
        var out = new ArrayList<PlannedBlock>();
        AssemblyCirculationOps.applyStairSystem(out, new MetaAssemblyEngine.Context(null, BlockPos.ORIGIN, Direction.SOUTH, null), BlockPos.ORIGIN,
            Map.of("from", point(0, 0, 0), "to", point(4, 4, 0), "width", 1, "carve", false, "support", false), adapter);
        var flight = AssemblyCirculationConstraints.capture(out, 0);
        assertTrue(flight.clearance().isEmpty()); assertEquals(5, flight.occupied().size());
        out.add(new PlannedBlock(new BlockPos(2, 4, 0), Blocks.STONE.getDefaultState()));
        out.add(new PlannedBlock(new BlockPos(2, 1, 0), Blocks.AIR.getDefaultState()));
        assertDoesNotThrow(() -> AssemblyCirculationConstraints.validate(out, List.of(flight)));
    }
    @Test void repeatedCompatibleFlightDoesNotCountAsAConflict() {
        var out = new ArrayList<PlannedBlock>();
        var first = flight(out, 0, 0, 0, 4, 4, 0);
        var second = flight(out, 0, 0, 0, 4, 4, 0);
        assertDoesNotThrow(() -> AssemblyCirculationConstraints.validate(out, List.of(first, second)));
    }

    @Test void captureUsesLastWriteSoAirThenSolidIsOnlyOccupied() {
        var out = new ArrayList<PlannedBlock>();
        var pos = new BlockPos(1, 3, 0);
        out.add(new PlannedBlock(pos, Blocks.AIR.getDefaultState()));
        out.add(new PlannedBlock(pos, Blocks.OAK_PLANKS.getDefaultState()));
        var flight = AssemblyCirculationConstraints.capture(out, 0);
        assertTrue(flight.occupied().contains(pos));
        assertFalse(flight.clearance().contains(pos));
    }

    @Test void laterFlightTreadFillingEarlierHeadroomIsRejectedInValidatePatches() {
        var earlier = new AssemblyCirculationConstraints.Flight(
                Set.of(new BlockPos(0, 0, 0)), Set.of(new BlockPos(1, 2, 0)));
        var later = new AssemblyCirculationConstraints.Flight(
                Set.of(new BlockPos(1, 2, 0)), Set.of(new BlockPos(1, 3, 0)));
        var patches = List.of(
                new com.formacraft.common.patch.BlockPatch(
                        com.formacraft.common.patch.BlockPatch.PLACE, 0, 0, 0, "minecraft:oak_planks"),
                new com.formacraft.common.patch.BlockPatch(
                        com.formacraft.common.patch.BlockPatch.PLACE, 1, 2, 0, "minecraft:oak_planks"),
                new com.formacraft.common.patch.BlockPatch(
                        com.formacraft.common.patch.BlockPatch.REMOVE, 1, 3, 0, "minecraft:air"));
        var error = assertThrows(AssemblyCirculationConstraints.Conflict.class,
                () -> AssemblyCirculationConstraints.validatePatches(patches, List.of(earlier, later)));
        assertTrue(error.getMessage().contains("clearance blocked"));
    }

    @Test void sharedLandingOccupiedByBothFlightsIsAllowed() {
        var platform = new BlockPos(2, 4, 0);
        var first = new AssemblyCirculationConstraints.Flight(
                Set.of(new BlockPos(0, 0, 0), platform), Set.of(new BlockPos(0, 1, 0)));
        var second = new AssemblyCirculationConstraints.Flight(
                Set.of(platform, new BlockPos(4, 4, 0)), Set.of(new BlockPos(4, 5, 0)));
        var patches = List.of(
                place(0, 0, 0, "minecraft:oak_planks"),
                remove(0, 1, 0),
                place(2, 4, 0, "minecraft:oak_planks"),
                place(4, 4, 0, "minecraft:oak_planks"),
                remove(4, 5, 0));
        assertDoesNotThrow(() -> AssemblyCirculationConstraints.validatePatches(patches, List.of(first, second)));
    }

    @Test void omittingEarlierFlightRevokesItsContractAfterExplicitReplace() {
        var earlier = new AssemblyCirculationConstraints.Flight(
                Set.of(new BlockPos(0, 0, 0)), Set.of(new BlockPos(1, 2, 0)));
        var replacement = new AssemblyCirculationConstraints.Flight(
                Set.of(new BlockPos(1, 2, 0)), Set.of(new BlockPos(1, 3, 0)));
        var patches = List.of(
                place(1, 2, 0, "minecraft:oak_planks"),
                remove(1, 3, 0));
        // Explicit replace: earlier flight omitted from the active contract list.
        assertDoesNotThrow(() -> AssemblyCirculationConstraints.validatePatches(patches, List.of(replacement)));
        assertThrows(AssemblyCirculationConstraints.Conflict.class,
                () -> AssemblyCirculationConstraints.validatePatches(patches, List.of(earlier, replacement)));
    }

    private static com.formacraft.common.patch.BlockPatch place(int x, int y, int z, String block) {
        return new com.formacraft.common.patch.BlockPatch(
                com.formacraft.common.patch.BlockPatch.PLACE, x, y, z, block);
    }

    private static com.formacraft.common.patch.BlockPatch remove(int x, int y, int z) {
        return new com.formacraft.common.patch.BlockPatch(
                com.formacraft.common.patch.BlockPatch.REMOVE, x, y, z, "minecraft:air");
    }
}
