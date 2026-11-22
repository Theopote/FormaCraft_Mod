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
    void assemblyStairsUseTheFloorFamilyOnlyWhenTreadsAreOmitted() {
        var op = new HashMap<String, Object>(Map.of(
                "op", "STAIR_SYSTEM", "floor", "minecraft:smooth_quartz",
                "from", Map.of("x", 0, "y", 0, "z", 0),
                "to", Map.of("x", 0, "y", 2, "z", 3)));
        var ctx = new MetaAssemblyEngine.Context(null, BlockPos.ORIGIN, Direction.SOUTH, null);
        var engine = new MetaAssemblyEngine();
        var implicit = engine.executeGeometry(AssemblySpec.of(null, null, List.of(op)), ctx);
        assertTrue(implicit.stream().anyMatch(block -> block.getTargetState().isOf(Blocks.SMOOTH_QUARTZ_STAIRS)));
        op.put("stairs", "minecraft:oak_stairs[waterlogged=true]");
        var explicit = engine.executeGeometry(AssemblySpec.of(null, null, List.of(op)), ctx);
        assertTrue(explicit.stream().anyMatch(block -> block.getTargetState().isOf(Blocks.OAK_STAIRS)
                && block.getTargetState().get(Properties.WATERLOGGED)));
        assertFalse(explicit.stream().anyMatch(block -> block.getTargetState().isOf(Blocks.SMOOTH_QUARTZ_STAIRS)));
        op.put("stairs", "minecraft:cracked_deepslate_brick_stairs");
        var issue = assertThrows(AssemblyCirculationOps.InvalidStairMaterial.class,
                () -> engine.executeGeometry(AssemblySpec.of(null, null, List.of(op)), ctx));
        assertTrue(issue.getMessage().contains("minecraft:cracked_deepslate_brick_stairs"));
    }

    @Test
    void assemblyRequiresMatchingTreadsOnlyForFlightsWithRise() {
        var op = new HashMap<String, Object>(Map.of(
                "op", "STAIR_SYSTEM", "floor", "minecraft:white_concrete",
                "from", Map.of("x", 0, "y", 0, "z", 0), "to", Map.of("x", 0, "y", 2, "z", 3)));
        var context = new MetaAssemblyEngine.Context(null, BlockPos.ORIGIN, Direction.SOUTH, null);
        var engine = new MetaAssemblyEngine();
        var issue = assertThrows(AssemblyCirculationOps.InvalidStairMaterial.class,
                () -> engine.executeGeometry(AssemblySpec.of(null, null, List.of(op)), context));
        assertTrue(issue.getMessage().contains("minecraft:white_concrete"));
        op.put("stairs", "minecraft:oak_stairs[waterlogged=true]");
        var flight = snapshot(engine.executeGeometry(AssemblySpec.of(null, null, List.of(op)), context));
        assertTrue(flight.values().stream().anyMatch(state -> state.isOf(Blocks.OAK_STAIRS)
                && state.get(Properties.WATERLOGGED)));
        op.remove("stairs");
        op.put("to", Map.of("x", 0, "y", 0, "z", 3));
        var landing = snapshot(engine.executeGeometry(AssemblySpec.of(null, null, List.of(op)), context));
        assertEquals(Blocks.WHITE_CONCRETE.getDefaultState(), landing.get(BlockPos.ORIGIN));
        assertFalse(landing.values().stream().anyMatch(state -> state.getBlock() instanceof StairsBlock));
    }

    @Test
    void assemblyStairsKeepExplicitFloorAndSupportStates() {
        var op = new HashMap<String, Object>(Map.of(
                "op", "STAIR_SYSTEM", "floor", "minecraft:smooth_quartz_slab[type=top]",
                "supportMaterial", "minecraft:oak_log[axis=x]",
                "from", Map.of("x", 0, "y", 0, "z", 0), "to", Map.of("x", 0, "y", 2, "z", 3)));
        var ctx = new MetaAssemblyEngine.Context(null, BlockPos.ORIGIN, Direction.SOUTH, null);
        var engine = new MetaAssemblyEngine();
        var blocks = snapshot(engine.executeGeometry(AssemblySpec.of(null, null, List.of(op)), ctx));
        assertEquals(net.minecraft.block.enums.SlabType.TOP, blocks.get(BlockPos.ORIGIN).get(Properties.SLAB_TYPE));
        assertEquals(Direction.Axis.X, blocks.get(BlockPos.ORIGIN.down()).get(Properties.AXIS));
        op.put("supportMaterial", "minecraft:oak_log[axis=bad]");
        var issue = assertThrows(AssemblyCirculationOps.InvalidStairMaterial.class,
                () -> engine.executeGeometry(AssemblySpec.of(null, null, List.of(op)), ctx));
        assertTrue(issue.getMessage().contains("supportMaterial"));
    }

    @Test
    void aSweepAndCableUseModelAuthoredPathsAndSharedAnchors() {
        var start = Map.of("x", 0, "y", 4, "z", 0);
        var end = Map.of("x", 8, "y", 4, "z", 0);
        var sweep = Map.of("op", "SPLINE_SWEEP", "points", List.of(start, Map.of("x", 4, "y", 7, "z", 0), end),
                "profile", "SPHERE", "r", 1, "material", "minecraft:white_concrete");
        var cable = Map.of("op", "TENSION_CABLE", "from", start, "to", end, "sag", 2,
                "material", "minecraft:iron_bars");
        var payload = Map.of("ops", List.of(sweep, cable));
        assertTrue(AssemblySpecValidator.validate(payload).stream()
                .noneMatch(issue -> issue.severity() == AssemblyValidationIssue.Severity.ERROR));
        var context = new MetaAssemblyEngine.Context(null, BlockPos.ORIGIN, Direction.SOUTH, null);
        var blocks = snapshot(new MetaAssemblyEngine().executeGeometry(AssemblySpec.fromExtra(payload), context));
        assertEquals(Blocks.WHITE_CONCRETE.getDefaultState(), blocks.get(new BlockPos(4, 7, 0)));
        assertEquals(Blocks.IRON_BARS.getDefaultState(), blocks.get(new BlockPos(4, 2, 0)));
        assertEquals(Blocks.IRON_BARS.getDefaultState(), blocks.get(new BlockPos(0, 4, 0)));
        assertEquals(Blocks.IRON_BARS.getDefaultState(), blocks.get(new BlockPos(8, 4, 0)));
    }

    @Test
    void bezierPatchesWithSharedControlsProduceMatchingCurvedEdges() {
        var context = new MetaAssemblyEngine.Context(null, BlockPos.ORIGIN, Direction.SOUTH, null);
        var engine = new MetaAssemblyEngine();
        var left = snapshot(engine.executeGeometry(AssemblySpec.of(null, null, List.of(bezierPatch(0))), context));
        var right = snapshot(engine.executeGeometry(AssemblySpec.of(null, null, List.of(bezierPatch(9))), context));
        var leftEdge = left.keySet().stream().filter(pos -> pos.getX() == 9).toList();
        var rightEdge = right.keySet().stream().filter(pos -> pos.getX() == 9).toList();
        assertTrue(leftEdge.contains(new BlockPos(9, 6, 5)));
        assertEquals(new HashSet<>(leftEdge), new HashSet<>(rightEdge));
        assertEquals(Blocks.SMOOTH_QUARTZ.getDefaultState(), left.get(new BlockPos(9, 6, 5)));
        assertFalse(left.containsKey(new BlockPos(4, 0, 5)));
    }

    private static Map<String, Object> bezierPatch(int startX) {
        var points = new ArrayList<List<Map<String, Integer>>>();
        for (int x = startX; x <= startX + 9; x += 3) {
            points.add(List.of(Map.of("x", x, "y", 0, "z", 0), Map.of("x", x, "y", 8, "z", 3),
                    Map.of("x", x, "y", 8, "z", 6), Map.of("x", x, "y", 0, "z", 9)));
        }
        return Map.of("op", "BEZIER_SURFACE", "points", points, "uSamples", 12, "vSamples", 12,
                "material", "minecraft:smooth_quartz");
    }

    @Test
    void modelAuthoredRevolveProfilesProduceIndependentHollowTiers() {
        var lower = Map.of("op", "REVOLVE_SURFACE", "profilePoints",
                List.of(Map.of("x", 8, "y", 4), Map.of("x", 5, "y", 7)),
                "material", "minecraft:smooth_quartz");
        var upper = Map.of("op", "REVOLVE_SURFACE", "profilePoints",
                List.of(Map.of("x", 5, "y", 9), Map.of("x", 2, "y", 12)),
                "material", "minecraft:prismarine");
        var payload = Map.of("ops", List.of(lower, upper));
        assertTrue(AssemblySpecValidator.validate(payload).stream()
                .noneMatch(issue -> issue.severity() == AssemblyValidationIssue.Severity.ERROR));
        var context = new MetaAssemblyEngine.Context(null, BlockPos.ORIGIN, Direction.SOUTH, null);
        var blocks = snapshot(new MetaAssemblyEngine().executeGeometry(AssemblySpec.fromExtra(payload), context));
        assertEquals(Blocks.SMOOTH_QUARTZ.getDefaultState(), blocks.get(new BlockPos(8, 4, 0)));
        assertEquals(Blocks.PRISMARINE.getDefaultState(), blocks.get(new BlockPos(0, 9, 5)));
        assertTrue(blocks.containsKey(new BlockPos(-2, 12, 0)));
        assertFalse(blocks.containsKey(new BlockPos(0, 9, 0)));
    }

    @Test
    void modelAuthoredLoftProducesSlopedSurfaceAcrossSections() {
        var start = Map.of("at", Map.of("z", 0), "profilePoints",
                List.of(Map.of("x", 0, "y", 2), Map.of("x", 8, "y", 2)));
        var end = Map.of("at", Map.of("z", 12), "profilePoints",
                List.of(Map.of("x", 2, "y", 6), Map.of("x", 6, "y", 6)));
        var op = Map.of("op", "LOFT_SURFACE", "sections", List.of(start, end));
        var payload = Map.of("ops", List.of(op));
        assertTrue(AssemblySpecValidator.validate(payload).stream()
                .noneMatch(issue -> issue.severity() == AssemblyValidationIssue.Severity.ERROR));
        var ctx = new MetaAssemblyEngine.Context(null, BlockPos.ORIGIN, Direction.SOUTH, null);
        var blocks = snapshot(new MetaAssemblyEngine().executeGeometry(AssemblySpec.fromExtra(payload), ctx));
        assertTrue(blocks.containsKey(new BlockPos(4, 2, 0)));
        assertTrue(blocks.containsKey(new BlockPos(4, 4, 6)));
        assertTrue(blocks.containsKey(new BlockPos(4, 6, 12)));
        assertFalse(blocks.containsKey(new BlockPos(4, 2, 12)));
        var shortSection = Map.of("profilePoints", List.of(Map.of("x", 0, "y", 2)));
        assertTrue(AssemblySpecValidator.validate(Map.of("ops", List.of(Map.of("op", "LOFT_SURFACE",
                "sections", List.of(start, shortSection))))).stream()
                .anyMatch(issue -> issue.code().equals("E_LOFT_PROFILE_INVALID")));
        var longerSection = Map.of("rings", List.of(List.of(
                Map.of("x", 0, "y", 2), Map.of("x", 4, "y", 3), Map.of("x", 8, "y", 2))));
        var mismatch = AssemblySpecValidator.validate(Map.of("ops", List.of(Map.of("op", "LOFT_SURFACE",
                "sections", List.of(start, longerSection))))).stream()
                .filter(issue -> issue.code().equals("E_LOFT_PROFILE_COUNT")).findFirst().orElseThrow();
        assertEquals("$.ops[0].sections[1].rings", mismatch.path());
        assertEquals("轮廓点数应为 2，实际为 3", mismatch.message());
    }

    @Test
    void shellPortsFollowBaseTwistWhileCustomPortsKeepTheirCoordinates() {
        var shell = shell(0.125);
        shell.put("w", 12);
        shell.put("d", 10);
        shell.put("twistPhase", 0.25);
        shell.put("ports", Map.of("canopy", Map.of("x", 2, "y", 0, "z", -4)));
        var ports = AssemblyPortResolver.ports(shell);
        assertArrayEquals(new int[]{0, 0, 5}, ports.get("east"));
        assertArrayEquals(new int[]{-4, 0, 5}, ports.get("front_right"));
        assertArrayEquals(new int[]{2, 0, -4}, ports.get("canopy"));
        var blocks = snapshot(generate(shell));
        for (String name : List.of("north", "south", "east", "west", "nw", "ne", "sw", "se")) {
            int[] pos = ports.get(name);
            assertEquals(Blocks.STONE_BRICKS.getDefaultState(), blocks.get(new BlockPos(pos[0], pos[1], pos[2])));
        }
    }

    @Test
    void graphShellTopPortsUseTheEmittedDefaultHeight() {
        var shell = new HashMap<String, Object>(Map.of("id", "shell", "type", "BOX_SHELL"));
        for (int expected : List.of(18, 26)) {
            var spec = MetaAssemblyCompiler.compile(Map.of("graph", Map.of("components", List.of(shell))), null);
            assertNotNull(spec);
            var op = spec.ops.stream().filter(item -> "SHELL_BOX".equals(item.get("op"))).findFirst().orElseThrow();
            assertEquals(expected, op.get("h"));
            assertArrayEquals(new int[]{0, expected, 0}, AssemblyPortResolver.ports(shell).get("top_center"));
            shell.put("height", 26);
        }
    }

    @Test
    void graphShellUsesLongFormDimensionsForGeometryAndPorts() {
        var shell = Map.<String, Object>of("id", "shell", "type", "BOX_SHELL",
                "width", 12, "depth", 10, "height", 28, "h", 26);
        var spec = MetaAssemblyCompiler.compile(Map.of("graph", Map.of("components", List.of(shell))), null);
        assertNotNull(spec);
        var op = spec.ops.stream().filter(item -> "SHELL_BOX".equals(item.get("op"))).findFirst().orElseThrow();
        assertEquals(12, op.get("w"));
        assertEquals(10, op.get("d"));
        assertEquals(26, op.get("h"));
        var ports = AssemblyPortResolver.ports(shell);
        assertArrayEquals(new int[]{5, 0, 4}, ports.get("front_right"));
        assertArrayEquals(new int[]{0, 26, 0}, ports.get("top_center"));
        assertEquals(120, snapshot(generate(op)).keySet().stream().filter(pos -> pos.getY() == 0).count());
    }

    @Test
    void evenShellPortsTouchTheBaseWallCells() {
        var shell = shell(0);
        shell.put("w", 12);
        shell.put("d", 10);
        var ports = AssemblyPortResolver.ports(shell);
        var blocks = snapshot(generate(shell));
        assertArrayEquals(new int[]{5, 0, 0}, ports.get("east"));
        assertArrayEquals(new int[]{0, 0, 4}, ports.get("south"));
        assertArrayEquals(new int[]{5, 0, 4}, ports.get("front_right"));
        for (String key : List.of("north", "south", "east", "west", "nw", "ne", "sw", "se")) {
            int[] cell = ports.get(key);
            assertEquals(Blocks.STONE_BRICKS.getDefaultState(), blocks.get(new BlockPos(cell[0], cell[1], cell[2])));
        }
    }

    @Test
    void graphValidationUsesTheExecutorPortSpellingRules() {
        var component = Map.of("id", "shell", "type", "SHELL_BOX",
                "w", 12, "d", 12, "h", 28,
                "ports", Map.of("Canopy Mount", Map.of("x", 1)));
        var graph = Map.of("components", List.of(component), "connections", List.of(
                Map.of("from", "shell.N", "to", "shell.top-center"),
                Map.of("from", "shell.canopy-mount", "to", "shell.center")));
        var issues = AssemblySpecValidator.validate(Map.of("graph", graph));
        assertTrue(issues.stream().noneMatch(issue -> issue.severity() == AssemblyValidationIssue.Severity.ERROR),
                issues::toString);
    }

    @Test
    void rawCoordinatesCannotSilentlyDropAGraphConnection() {
        var graph = Map.of("components", List.of(Map.of("id", "shell", "type", "SHELL_BOX")),
                "connections", List.of(Map.of("from", Map.of("x", 3, "y", 0, "z", 0), "to", "shell.center")));
        var issues = AssemblySpecValidator.validate(Map.of("graph", graph));
        assertTrue(issues.stream().anyMatch(issue -> issue.code().equals("E_ENDPOINT_COMPONENT_REQUIRED")
                && issue.path().equals("$.graph.connections[0].from")), issues::toString);
    }

    @Test
    void authoredGraphCannotMaskTopLevelConnections() {
        var graph = Map.of("components", List.of(Map.of("id", "shell", "type", "SHELL_BOX")));
        var conflict = Map.of("graph", graph,
                "connections", List.of(Map.of("from", "shell.center", "to", "shell.top_center")));
        var issue = AssemblySpecValidator.authoredGraphLocationIssue(
                AssemblySpecNormalizer.normalize(conflict).normalized());
        assertNotNull(issue);
        assertEquals("E_ASSEMBLY_GRAPH_LOCATION", issue.code());
        assertEquals("$.connections", issue.path());
        assertNull(AssemblySpecValidator.authoredGraphLocationIssue(Map.of("graph", graph, "connections", List.of())));
    }

    @Test
    void mixedGeometryCannotSilentlyDropTheGraph() {
        var ops = List.of(Map.of("op", "SHELL_BOX", "w", 12, "d", 12, "h", 28));
        var graph = Map.of("components", List.of(Map.of("id", "shell",
                "type", "SHELL_BOX", "w", 12, "d", 12, "h", 28)));
        var issues = AssemblySpecValidator.validate(Map.of("ops", ops, "graph", graph));
        assertTrue(issues.stream().anyMatch(issue -> issue.code().equals("E_ASSEMBLY_MIXED_GEOMETRY")
                && issue.path().equals("$.graph.components")), issues::toString);
    }

    @Test
    void executableOpsDoNotMaskMalformedGraphComponents() {
        var ops = List.of(Map.of("op", "SHELL_BOX", "w", 12, "d", 12, "h", 28));
        var invalidGraph = AssemblySpecValidator.validate(Map.of("ops", ops, "graph", List.of()));
        assertTrue(invalidGraph.stream().anyMatch(issue -> issue.code().equals("E_GRAPH_NOT_OBJECT")
                && issue.path().equals("$.graph")), invalidGraph::toString);
        var invalidComponents = AssemblySpecValidator.validate(Map.of("ops", ops,
                "graph", Map.of("components", Map.of())));
        assertTrue(invalidComponents.stream().anyMatch(issue -> issue.code().equals("E_COMPONENTS_NOT_ARRAY")
                && issue.path().equals("$.graph.components")), invalidComponents::toString);
    }

    @Test
    void graphRejectsConnectionsThatAreNotAnArray() {
        var graph = Map.of("graph", Map.of(
                "components", List.of(Map.of("id", "shell", "type", "SHELL_BOX")),
                "connections", Map.of("from", "shell.center", "to", "other.center")));
        var issues = AssemblySpecValidator.validate(graph);
        assertTrue(issues.stream().anyMatch(issue -> issue.code().equals("E_CONNECTIONS_NOT_ARRAY")
                && issue.path().equals("$.graph.connections")), issues::toString);
    }

    @Test
    void graphRejectsUnsupportedTypesAndPreservesExactAliases() {
        var graph = Map.of("graph", Map.of("components", List.of(
                Map.of("id", "shell", "type", "BOX_SHELL", "w", 12, "d", 12, "h", 28),
                Map.of("id", "offset", "type", "OFFSET_SURFACE", "source", Map.of()),
                Map.of("id", "unknown", "type", "CUSTOM_SHELL_BOX"))));
        var errors = AssemblySpecValidator.validate(graph).stream()
                .filter(issue -> issue.code().equals("E_COMPONENT_TYPE_UNSUPPORTED"))
                .map(AssemblyValidationIssue::path).toList();
        assertEquals(List.of("$.graph.components[2].type"), errors);
    }

    @Test
    void unsupportedOperationFailsValidationEvenWithAnExecutableShell() {
        var plan = Map.of("ops", List.of(
                Map.of("op", "SHELL_BOX", "w", 12, "d", 12, "h", 28),
                Map.of("op", "NOVEL_UNSUPPORTED_OP")));
        var issues = AssemblySpecValidator.validate(plan);
        assertTrue(issues.stream().anyMatch(issue -> issue.code().equals("E_OP_UNSUPPORTED")
                && issue.path().equals("$.ops[1].op")), issues::toString);
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
