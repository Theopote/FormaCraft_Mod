package com.formacraft.server.assembly;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.server.command.StyleVisualCases;
import com.formacraft.server.assembly.validation.AssemblySpecNormalizer;
import com.formacraft.server.assembly.validation.AssemblySpecValidator;
import com.formacraft.server.assembly.validation.AssemblyValidationIssue;
import com.formacraft.server.generation.component.impl.AssemblyPatchGenerator;
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
    @Test
    void ordinaryPlanPreservesAThirtyBlockTwistedEnvelope() throws Exception {
        var plan = StyleVisualCases.load("twisted_shell", new BlockPos(0, 64, 0));
        var component = plan.components().getFirst();
        assertTrue(AssemblyPatchGenerator.usesGeneratedTerrainFootprint(component));
        var preset = new com.formacraft.common.llm.dto.Component("ASSEMBLY", component.slotId(),
                component.relativePosition(), component.dimensions(), List.of(),
                Map.of("assembly", Map.of("preset", "spiral_watchtower")));
        assertFalse(AssemblyPatchGenerator.usesGeneratedTerrainFootprint(preset));
        var payload = AssemblyPatchGenerator.resolveAssemblyPayload(component.params());
        var normalized = AssemblySpecNormalizer.normalize(payload).normalized();
        var issues = AssemblySpecValidator.validate(normalized);
        assertTrue(issues.stream().noneMatch(issue -> issue.severity() == AssemblyValidationIssue.Severity.ERROR),
                issues::toString);
        var spec = AssemblySpec.fromExtra(normalized);
        assertNotNull(spec);
        assertEquals(0.125, ((Number) spec.ops.getFirst().get("twistTurns")).doubleValue());
        assertEquals(0.85, ((Number) spec.ops.getFirst().get("topScale")).doubleValue());

        var ctx = new MetaAssemblyEngine.Context(null, BlockPos.ORIGIN, Direction.SOUTH, null);
        var blocks = snapshot(new MetaAssemblyEngine().executeGeometry(spec, ctx));
        for (int y = 0; y < 29; y++) {
            int level = y;
            assertTrue(blocks.entrySet().stream().filter(entry -> entry.getKey().getY() == level)
                    .filter(entry -> !entry.getValue().isAir()).count() >= 36, "Missing wall at y=" + y);
        }
        assertTrue(blocks.values().stream().anyMatch(state -> state.isOf(Blocks.WHITE_STAINED_GLASS)));
        assertFalse(blocks.get(new BlockPos(0, 16, 0)).isAir());
        assertTrue(blocks.get(new BlockPos(0, 17, 0)).isAir());
        assertFalse(blocks.get(new BlockPos(0, 29, 0)).isAir());
        assertEquals(29, blocks.keySet().stream().mapToInt(BlockPos::getY).max().orElseThrow());
    }

    @Test
    void taperedTwistedShellUsesChangingSectionsForRoofFloorsAndClearing() {
        var op = shell(0.25);
        op.put("w", 12);
        op.put("d", 10);
        op.put("h", 32);
        op.put("topScale", 0.75);
        var result = snapshot(generate(op));

        assertEquals(120, result.keySet().stream().filter(pos -> pos.getY() == 0).count());
        assertEquals(72, result.keySet().stream().filter(pos -> pos.getY() == 33).count());
        assertEquals(Blocks.SMOOTH_STONE.getDefaultState(), result.get(new BlockPos(0, 16, 0)));
        assertTrue(result.get(new BlockPos(0, 17, 0)).isAir());
        assertNull(result.get(new BlockPos(5, 33, 0)));
    }

    @Test
    void explicitNeutralScalePreservesConstantShell() {
        var op = shell(0.125);
        var baseline = snapshot(generate(op));
        op.put("topScale", 1.0);
        assertEquals(baseline, snapshot(generate(op)));
    }

    @Test
    void rejectsUnsupportedScaleWithoutEmittingBlocks() {
        var op = shell(0);
        op.put("topScale", 1.01);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> generate(op))
                .getMessage().startsWith("E_SHELL_BOX_TOP_SCALE"));
        op.put("topScale", 0.75);
        op.put("wallMode", "COLONNADE");
        assertThrows(IllegalArgumentException.class, () -> generate(op));
    }

    @Test
    void colonnadeWallModePlacesCornerPostsWithoutSolidWallFill() {
        var op = shell(0);
        op.put("wallMode", "COLONNADE");
        op.put("postSpacing", 2);
        var result = snapshot(generate(op));
        // Corner posts present through height (w=15→halfW=7, d=11→halfD=5)
        assertEquals(Blocks.STONE_BRICKS.getDefaultState(), result.get(new BlockPos(-7, 3, -5)));
        assertEquals(Blocks.STONE_BRICKS.getDefaultState(), result.get(new BlockPos(7, 3, 5)));
        // Mid-bay on west edge between posts (z=0, y=3) must stay open — solid shell would fill it
        assertTrue(!result.containsKey(new BlockPos(-7, 3, 0)) || result.get(new BlockPos(-7, 3, 0)).isAir());
        long edgeWallCells = result.entrySet().stream()
                .filter(e -> e.getKey().getX() == -7 && e.getKey().getY() >= 1 && e.getKey().getY() < 9)
                .filter(e -> !e.getValue().isAir())
                .count();
        // Solid shell ≈ 8*11=88; colonnade ≈ 6 posts * 8 tall ≈ 48.
        assertTrue(edgeWallCells < 70, "expected open colonnade, got " + edgeWallCells + " west-edge cells");
        assertTrue(edgeWallCells >= 4, "expected at least corner/bay posts on west edge");
    }

    @Test
    void highOpennessAlsoSelectsColonnade() {
        var op = shell(0);
        op.put("openness", 0.8);
        var result = snapshot(generate(op));
        assertEquals(Blocks.STONE_BRICKS.getDefaultState(), result.get(new BlockPos(-7, 2, -5)));
        assertTrue(!result.containsKey(new BlockPos(-7, 2, 0)) || result.get(new BlockPos(-7, 2, 0)).isAir());
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

    @Test void closedShellKeepsEvenDimensionsBeforeAndAfterQuarterTurn() {
        var op = shell(0.25);
        op.put("w", 12);
        op.put("d", 10);
        var result = snapshot(generate(op));
        for (int y : new int[]{0, 10}) {
            var cells = result.entrySet().stream()
                    .filter(e -> e.getKey().getY() == y && !e.getValue().isAir())
                    .map(Map.Entry::getKey).toList();
            assertEquals(120, cells.size());
            assertEquals(y == 0 ? 12 : 10, cells.stream().map(BlockPos::getX).distinct().count());
            assertEquals(y == 0 ? 10 : 12, cells.stream().map(BlockPos::getZ).distinct().count());
        }
    }

    @Test void diagonalDecksCoverEveryCellInsideTheRotatedFootprint() {
        var op = shell(0);
        op.put("twistPhase", 0.125);
        var result = snapshot(generate(op));
        double axis = Math.sqrt(0.5);
        for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) {
            double localX = Math.abs((x + z) * axis);
            double localZ = Math.abs((z - x) * axis);
            var roofCell = new BlockPos(x, 10, z);
            if (localX <= 7.5 && localZ <= 5.5)
                assertEquals(Blocks.SMOOTH_STONE_SLAB.getDefaultState(), result.get(roofCell), roofCell.toShortString());
            else assertFalse(result.containsKey(roofCell), roofCell.toShortString());
            if (localX <= 6.5 && localZ <= 4.5) {
                var floorCell = new BlockPos(x, 4, z);
                assertEquals(Blocks.SMOOTH_STONE.getDefaultState(), result.get(floorCell), floorCell.toShortString());
            }
            if (localX < 5.5 && localZ < 3.5) {
                var roomCell = new BlockPos(x, 3, z);
                assertEquals(Blocks.AIR.getDefaultState(), result.get(roomCell), roomCell.toShortString());
            }
        }
    }

    @ParameterizedTest @ValueSource(doubles = {0.125, -0.125, 0.25})
    void twistedWallRowsStayFaceConnected(double twist) {
        var result = snapshot(generate(shell(twist)));
        for (int y : new int[]{1, 2, 3, 5, 6, 7, 9}) {
            int layer = y;
            var pending = new HashSet<BlockPos>();
            result.forEach((pos, state) -> {
                if (pos.getY() == layer && !state.isAir()) pending.add(pos);
            });
            assertFalse(pending.isEmpty());
            var queue = new ArrayDeque<BlockPos>();
            var start = pending.iterator().next();
            pending.remove(start);
            queue.add(start);
            while (!queue.isEmpty()) {
                var pos = queue.remove();
                for (var face : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
                    var next = pos.offset(face);
                    if (pending.remove(next)) queue.add(next);
                }
            }
            assertTrue(pending.isEmpty(), "Disconnected wall at y=" + y + ", twist=" + twist);
        }
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
