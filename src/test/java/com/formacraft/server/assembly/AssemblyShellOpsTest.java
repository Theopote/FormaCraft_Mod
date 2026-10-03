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

class AssemblyShellOpsTest {
    @BeforeAll static void initialize() { MinecraftRegistryTestBootstrap.initialize(); }
    private static class Adapter implements AssemblySolidOps.Adapter, AssemblyCirculationOps.Adapter {
        public void put(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin, int x, int y, int z, BlockState state) {
            out.add(new PlannedBlock(PlacementUtil.local(origin, ctx.entranceFacing(), x, y, z), PlacementUtil.rotateState(state, ctx.entranceFacing())));
        }
        public BlockState pick(MetaAssemblyEngine.Context ctx, Map<?, ?> op, String key, String semantic, long salt, BlockState fallback) { return fallback; }
        public int i(Object v, int fallback) { return v instanceof Number n ? n.intValue() : fallback; }
        public double d(Object v, double fallback) { return v instanceof Number n ? n.doubleValue() : fallback; }
        public boolean bool(Object v, boolean fallback) { return v instanceof Boolean b ? b : fallback; }
        public String str(Object v, String fallback) { return v == null ? fallback : v.toString(); }
        public int clamp(int v, int min, int max) { return Math.max(min, Math.min(v, max)); }
        public double clamp(double v, double min, double max) { return Math.max(min, Math.min(v, max)); }
    }
    private final Adapter adapter = new Adapter();
    private Map<String, Object> shell(double twist) {
        return new HashMap<>(Map.of("type", "SHELL_BOX", "w", 15, "d", 11, "h", 9, "floorStep", 4, "twistTurns", twist));
    }
    private List<PlannedBlock> generate(Map<String, Object> shell) {
        var out = new ArrayList<PlannedBlock>();
        AssemblyShellOps.applyShellBox(out, new MetaAssemblyEngine.Context(null, BlockPos.ORIGIN, Direction.SOUTH, null), BlockPos.ORIGIN, shell, adapter);
        return out;
    }
    private Map<BlockPos, BlockState> snapshot(List<PlannedBlock> out) {
        var map = new HashMap<BlockPos, BlockState>();
        for (var block : out) map.put(block.getPos(), block.getTargetState());
        return map;
    }
    @ParameterizedTest @ValueSource(doubles = {0, 0.25, -0.25})
    void everyFloorAndRoofSurviveInteriorClearing(double twist) {
        var result = snapshot(generate(shell(twist)));
        for (int y : new int[]{0, 4, 8}) assertEquals(Blocks.SMOOTH_STONE.getDefaultState(), result.get(new BlockPos(0, y, 0)));
        for (int y : new int[]{1, 3, 5, 7, 9}) assertTrue(result.get(new BlockPos(0, y, 0)).isAir());
        assertEquals(Blocks.SMOOTH_STONE_SLAB.getDefaultState(), result.get(new BlockPos(0, 10, 0)));
    }
    @ParameterizedTest @ValueSource(doubles = {0, 0.25, -0.25})
    void transformedWallsSurviveAndExteriorIsNotCleared(double twist) {
        var result = snapshot(generate(shell(twist)));
        for (int y = 1; y <= 9; y++) {
            double angle = twist * 2 * Math.PI * y / 9;
            var edge = new BlockPos((int) Math.round(7 * Math.cos(angle)), y, (int) Math.round(7 * Math.sin(angle)));
            assertNotNull(result.get(edge)); assertFalse(result.get(edge).isAir());
        }
        // This point was erased by the old twisted bounding-box clear.
        assertFalse(result.containsKey(new BlockPos(9, 3, 9)));
    }
    @Test void phaseRotatesRectangularShellEvenWithoutTwistTurns() {
        var op = shell(0); op.put("twistPhase", 0.25);
        var result = snapshot(generate(op));
        assertEquals(Blocks.STONE_BRICKS.getDefaultState(), result.get(new BlockPos(0, 3, 7)));
        assertFalse(result.containsKey(new BlockPos(7, 3, 0)));
        assertEquals(Blocks.SMOOTH_STONE.getDefaultState(), result.get(new BlockPos(0, 4, 6)));
    }
    @ParameterizedTest @ValueSource(strings = {"NORTH", "SOUTH", "EAST", "WEST"})
    void compiledShellThenStairsCutOpeningAndKeepUpperLanding(String facing) {
        var stairs = Map.<String, Object>of("type", "STAIR_SYSTEM", "from", Map.of("x", -4, "y", 0, "z", 0),
            "to", Map.of("x", 0, "y", 4, "z", 0), "width", 2, "clearHeight", 3);
        var spec = MetaAssemblyCompiler.compile(Map.of("components", List.of(shell(0), stairs)), null);
        assertNotNull(spec);
        var origin = new BlockPos(-30, 64, 20); var direction = Direction.valueOf(facing);
        var ctx = new MetaAssemblyEngine.Context(null, origin, direction, null);
        var out = new ArrayList<PlannedBlock>(); var stack = new ArrayDeque<BlockPos>(); var current = origin;
        var flights = new ArrayList<AssemblyCirculationConstraints.Flight>();
        // Exercise real graph emission and production geometry without a ServerWorld.
        for (var op : spec.ops) {
            switch (op.get("op").toString()) {
                case "PUSH_ORIGIN" -> { stack.push(current); current = current.add(adapter.i(op.get("dx"), 0), adapter.i(op.get("dy"), 0), adapter.i(op.get("dz"), 0)); }
                case "POP_ORIGIN" -> current = stack.pop();
                case "SHELL_BOX" -> AssemblyShellOps.applyShellBox(out, ctx, current, op, adapter);
                case "STAIR_SYSTEM" -> {
                    int start = out.size();
                    AssemblyCirculationOps.applyStairSystem(out, ctx, current, op, adapter);
                    flights.add(AssemblyCirculationConstraints.capture(out, start));
                }
                default -> fail("Unexpected compiled operation: " + op);
            }
        }
        assertDoesNotThrow(() -> AssemblyCirculationConstraints.validate(out, flights));
        var result = snapshot(out);
        for (int x = -3; x <= 0; x++) for (int z : new int[]{-1, 0}) {
            int y = x + 4;
            var tread = result.get(PlacementUtil.local(origin, direction, x, y, z));
            assertInstanceOf(StairsBlock.class, tread.getBlock());
            assertEquals(PlacementUtil.rotateState(Blocks.STONE_BRICK_STAIRS.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.EAST), direction), tread);
            for (int h = 1; h <= 3; h++) assertTrue(result.get(PlacementUtil.local(origin, direction, x, y + h, z)).isAir());
        }
        assertTrue(result.get(PlacementUtil.local(origin, direction, -3, 4, 0)).isAir());
        for (int z : new int[]{-1, 0}) assertEquals(Blocks.SMOOTH_STONE.getDefaultState(), result.get(PlacementUtil.local(origin, direction, 1, 4, z)));
        assertEquals(Blocks.SMOOTH_STONE.getDefaultState(), result.get(PlacementUtil.local(origin, direction, 3, 8, 2)));
    }
}
