package com.formacraft.server.assembly;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.*;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AssemblyCirculationOpsTest {
    @BeforeAll static void initialize() { MinecraftRegistryTestBootstrap.initialize(); }
    private final AssemblyCirculationOps.Adapter adapter = new AssemblyCirculationOps.Adapter() {
        public void put(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin, int x, int y, int z, BlockState state) {
            out.add(new PlannedBlock(PlacementUtil.local(origin, ctx.entranceFacing(), x, y, z), PlacementUtil.rotateState(state, ctx.entranceFacing())));
        }
        public BlockState pick(MetaAssemblyEngine.Context ctx, Map<?, ?> op, String key, String semantic, long salt, BlockState fallback) { return fallback; }
        public int i(Object value, int fallback) { return value instanceof Number n ? n.intValue() : fallback; }
        public boolean bool(Object value, boolean fallback) { return value instanceof Boolean b ? b : fallback; }
        public int clamp(int value, int min, int max) { return Math.max(min, Math.min(value, max)); }
    };
    private Map<String, Object> point(int x, int y, int z) { return Map.of("x", x, "y", y, "z", z); }
    private Map<String, Object> op(int rise, int width) {
        return new HashMap<>(Map.of("from", point(0, 0, 0), "to", point(4, rise, 0), "width", width, "clearHeight", 3));
    }
    private List<PlannedBlock> generate(Map<String, Object> op, Direction entrance, BlockPos origin) {
        var out = new ArrayList<PlannedBlock>();
        AssemblyCirculationOps.applyStairSystem(out, new MetaAssemblyEngine.Context(null, origin, entrance, null), origin, op, adapter);
        return out;
    }
    private Map<BlockPos, BlockState> snapshot(List<PlannedBlock> out) {
        var map = new HashMap<BlockPos, BlockState>();
        for (var block : out) map.put(block.getPos(), block.getTargetState());
        return map;
    }
    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 15})
    void widthIsExactAndBothEndpointsHaveTreadsSupportAndClearance(int width) {
        var result = snapshot(generate(op(4, width), Direction.SOUTH, BlockPos.ORIGIN));
        assertEquals(4L * width, result.values().stream().filter(s -> !s.isAir() && s.getBlock() != Blocks.SMOOTH_STONE).count());
        for (int x = 0; x <= 4; x++) for (int lane = 0; lane < width; lane++) {
            int z = -(width / 2) + lane;
            assertFalse(result.get(new BlockPos(x, x, z)).isAir());
            assertEquals(Blocks.SMOOTH_STONE.getDefaultState(), result.get(new BlockPos(x, x - 1, z)));
            for (int h = 1; h <= 3; h++) assertTrue(result.get(new BlockPos(x, x + h, z)).isAir());
        }
        assertEquals(5 * width * 5, result.size());
    }
    @ParameterizedTest @ValueSource(strings = {"NORTH", "SOUTH", "EAST", "WEST"})
    void finalStateDirectionRotatesWithAssemblyCoordinates(String name) {
        var entrance = Direction.valueOf(name); var origin = new BlockPos(10, 50, -20);
        var result = snapshot(generate(op(4, 1), entrance, origin));
        for (int x = 1; x <= 4; x++) {
            var state = result.get(PlacementUtil.local(origin, entrance, x, x, 0));
            assertInstanceOf(StairsBlock.class, state.getBlock());
            var expected = PlacementUtil.rotateState(Blocks.STONE_BRICK_STAIRS.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.EAST), entrance);
            assertEquals(expected, state);
        }
    }
    @Test void descendingTreadsFaceBackTowardTheHigherStep() {
        var op = op(0, 1); op.put("from", point(0, 4, 0));
        var result = snapshot(generate(op, Direction.SOUTH, BlockPos.ORIGIN));
        for (int x = 1; x <= 4; x++) assertEquals(Direction.WEST,
            result.get(new BlockPos(x, 4 - x, 0)).get(Properties.HORIZONTAL_FACING));
        assertNotNull(result.get(new BlockPos(4, 0, 0)));
    }
    @Test void gradualRiseHasContinuousColumnsAndFlatLandings() {
        var op = op(2, 1); var result = snapshot(generate(op, Direction.SOUTH, BlockPos.ORIGIN));
        int previous = 0;
        for (int x = 0; x <= 4; x++) {
            int y = (int) Math.round(x / 2.0); var state = result.get(new BlockPos(x, y, 0));
            assertNotNull(state); assertTrue(y - previous <= 1);
            assertEquals(y != previous, state.getBlock() instanceof StairsBlock);
            previous = y;
        }
    }
    @Test void invalidFlightIsRejectedBeforeEmittingAnyBlocks() {
        for (var endpoint : List.of(point(0, 5, 0), point(2, 5, 0), point(4, 2, 4), point(5000, 1, 0))) {
            var op = op(2, 1); op.put("to", endpoint); var out = new ArrayList<PlannedBlock>();
            assertThrows(IllegalArgumentException.class, () -> AssemblyCirculationOps.applyStairSystem(out,
                new MetaAssemblyEngine.Context(null, BlockPos.ORIGIN, Direction.SOUTH, null), BlockPos.ORIGIN, op, adapter));
            assertTrue(out.isEmpty());
        }
    }
    @Test void invalidCoordinatesAndExcessiveOutputAreRejected() {
        assertNotNull(AssemblyCirculationOps.flightError(point(0, 0, 0), Map.of("x", 1.5, "y", 2, "z", 0)));
        assertNotNull(AssemblyCirculationOps.flightError("ground", point(4, 2, 0)));
        var op = op(2, 15); op.put("to", point(4096, 2, 0)); op.put("clearHeight", 16);
        assertThrows(IllegalArgumentException.class, () -> generate(op, Direction.SOUTH, BlockPos.ORIGIN));
    }
    @Test void invalidFlightIsReportedByOpsAndComponentGraphValidation() {
        var operation = op(5, 1); operation.put("to", point(2, 5, 0));
        operation.put("op", "STAIR_SYSTEM");
        var component = new HashMap<String, Object>(operation);
        component.remove("op"); component.put("type", "STAIRCASE");
        for (var spec : List.of(Map.of("ops", List.of(operation)),
                Map.of("graph", Map.of("components", List.of(component))))) {
            assertTrue(com.formacraft.server.assembly.validation.AssemblySpecValidator.validate(spec).stream()
                .anyMatch(issue -> "E_STAIR_FLIGHT_INVALID".equals(issue.code())));
        }
    }
    @Test void carveAndSupportCanBeDisabled() {
        var op = op(2, 2); op.put("carve", false); op.put("support", false);
        var out = generate(op, Direction.SOUTH, BlockPos.ORIGIN);
        assertEquals(10, out.size()); assertTrue(out.stream().noneMatch(b -> b.getTargetState().isAir()));
    }
}
