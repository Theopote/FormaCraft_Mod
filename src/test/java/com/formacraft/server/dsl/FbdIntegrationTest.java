package com.formacraft.server.dsl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.formacraft.common.dsl.*;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.palette.dynamic.MaterialFallbackPolicy;
import com.formacraft.server.assembly.*;
import com.formacraft.server.compiler.ComponentPlanCompiler;
import com.formacraft.server.compiler.PatchOccupancyIndex;
import com.formacraft.server.compiler.PatchConflictValidator;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.generation.component.impl.AssemblyPatchGenerator;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FbdIntegrationTest {
    @BeforeAll static void bootstrap() { MinecraftRegistryTestBootstrap.initialize(); }
    @BeforeEach void reset() { AssemblyCompileDiagnostics.clear(); }

    private Map<String, Object> program(Object... body) { return Map.of("dialect", "fbd/0", "seed", 1, "main", List.of(body)); }
    private Object shell(String id) { return Map.of("stmt", "emit", "id", id, "op", "SHELL_BOX",
            "params", Map.of("w", 7, "d", 7, "h", 6, "wall", "wall", "window", "window", "roof", "slab")); }
    private Component component(String type, Map<String, Object> params) {
        return new Component(type, "program", new Vec3i(0, 0, 0), new Dimensions(7, 7, 8), List.of(), params);
    }

    @Test void loweringUsesExistingAssemblyRouteWithoutMutatingOriginalProgram() {
        var p = program(shell("shell"));
        var c = component("ASSEMBLY", Map.of("fbd_program", p));
        var result = FbdPlanLowering.lower(List.of(c));
        assertFalse(AssemblyCompileDiagnostics.hasGap());
        assertSame(p, c.params().get("fbd_program"));
        assertFalse(c.params().containsKey(FbdPlanLowering.COMPILED));
        assertInstanceOf(FbdCompiler.Compilation.class, result.getFirst().params().get(FbdPlanLowering.COMPILED));
        assertNotNull(AssemblyPatchGenerator.resolveAssemblyPayload(result.getFirst().params()));
        assertEquals("ASSEMBLY", result.getFirst().params().get("generation_authority"));
    }

    @ParameterizedTest @ValueSource(strings={"NORTH", "SOUTH", "EAST", "WEST"})
    void realGeometryIsReproducibleAndMaterialRolesDoNotBecomeAir(String facing) {
        var p = program(Map.of("stmt", "with_frame", "id", "frame", "origin", List.of(10, 0, 0), "body", List.of(shell("shell"))));
        var compiled = FbdCompiler.compile(p);
        var spec = FbdAssemblyAdapter.spec(compiled);
        var origin = new BlockPos(100, 64, -30);
        var direction = Direction.valueOf(facing);
        var engine = new MetaAssemblyEngine();
        var first = engine.executeGeometry(spec, new MetaAssemblyEngine.Context(null, origin, direction, null,
                new FbdExecutionBudget(FbdLimits.DEFAULT)));
        var second = engine.executeGeometry(spec, new MetaAssemblyEngine.Context(null, origin, direction, null,
                new FbdExecutionBudget(FbdLimits.DEFAULT)));
        var snapshot = new LinkedHashMap<BlockPos, Object>();
        var replay = new LinkedHashMap<BlockPos, Object>();
        first.forEach(b -> snapshot.put(b.getPos(), b.getTargetState()));
        second.forEach(b -> replay.put(b.getPos(), b.getTargetState()));
        assertEquals(snapshot, replay);
        var frame = origin.add(PlacementUtil.local(BlockPos.ORIGIN, direction, 10, 0, 0));
        assertEquals(Blocks.STONE_BRICKS.getDefaultState(), snapshot.get(PlacementUtil.local(frame, direction, -3,1,0)));
        assertEquals(Blocks.SMOOTH_STONE_SLAB.getDefaultState(), snapshot.get(PlacementUtil.local(frame, direction, 0,7,0)));
        assertTrue(first.size() <= compiled.cost());
    }

    @Test void suppliedGeometryExamplesExecuteWithinTheirConservativeCost() throws Exception {
        var mapper = new ObjectMapper();
        for (String name : List.of("01_two_wing_library.json", "02_plinth_pavilions.json", "04_randomized_roof.json")) {
            Map<String, Object> p = mapper.readValue(Files.readString(Path.of("src/test/resources/fbd/" + name)), new TypeReference<>() {});
            var compiled = FbdCompiler.compile(p);
            var blocks = new MetaAssemblyEngine().executeGeometry(FbdAssemblyAdapter.spec(compiled),
                    new MetaAssemblyEngine.Context(null, BlockPos.ORIGIN, Direction.SOUTH, null, new FbdExecutionBudget(FbdLimits.DEFAULT)));
            assertFalse(blocks.isEmpty(), name);
            assertTrue(blocks.size() <= compiled.cost(), name);
        }
    }

    @Test void realGameResponsesSurviveMaterialPreprocessingBeforeLoweringAndExecution() throws Exception {
        var mapper = new ObjectMapper();
        var responses = mapper.readTree(Files.readString(Path.of("src/test/resources/game_replays/fbd_20261024.json")));
        assertEquals(3, responses.size());
        for (var response : responses) {
            AssemblyCompileDiagnostics.clear();
            var parsed = LlmPlanParser.parse(response.toString());
            var normalized = MaterialFallbackPolicy.normalize(parsed);
            for (int i = 0; i < parsed.components().size(); i++)
                assertEquals(parsed.components().get(i).params().get("fbd_program"),
                        normalized.components().get(i).params().get("fbd_program"));
            var lowered = FbdPlanLowering.lower(normalized.components());
            assertFalse(AssemblyCompileDiagnostics.hasGap(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
            assertEquals(parsed.components().size(), lowered.size());
            for (var component : lowered) {
                var compilation = (FbdCompiler.Compilation) component.params().get(FbdPlanLowering.COMPILED);
                var blocks = new MetaAssemblyEngine().executeGeometry(FbdAssemblyAdapter.spec(compilation),
                        new MetaAssemblyEngine.Context(null, BlockPos.ORIGIN, Direction.NORTH, null,
                                new FbdExecutionBudget(FbdLimits.DEFAULT)));
                assertFalse(blocks.isEmpty());
                assertTrue(blocks.stream().anyMatch(b -> b.getTargetState().isOf(Blocks.STONE_BRICKS)));
            }
        }
    }

    @Test void materialFallbackStillNormalizesOrdinaryParamsBesideAnOpaqueProgram() {
        var ast = program(Map.of("stmt", "let", "id", "floor", "name", "floor", "value", "floor"), shell("shell"));
        var params = Map.<String, Object>of("fbd_program", ast, "floor", "floor");
        var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).anchor(new Vec3i(0,64,0))
                .styleProfile("DEFAULT").components(List.of(component("ASSEMBLY", params))).build();
        var normalized = MaterialFallbackPolicy.normalize(plan);
        assertSame(ast, normalized.components().getFirst().params().get("fbd_program"));
        assertEquals("minecraft:stone_bricks", normalized.components().getFirst().params().get("floor"));
        assertEquals("floor", params.get("floor"));
    }

    @Test void latestGamePlansPassPatchMergeWithoutGrantingOverwritePrivileges() throws Exception {
        var mapper = new ObjectMapper();
        var responses = mapper.readTree(Files.readString(Path.of("src/test/resources/game_replays/fbd_20261024_patch_merge.json")));
        assertEquals(5, responses.size());
        for (var response : responses) {
            AssemblyCompileDiagnostics.clear();
            var parsed = MaterialFallbackPolicy.normalize(LlmPlanParser.parse(response.toString()));
            var lowered = FbdPlanLowering.lower(parsed.components());
            assertFalse(AssemblyCompileDiagnostics.hasGap(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
            assertEquals(parsed.components().size(), lowered.size());
            var occupancy = new PatchOccupancyIndex();
            var all = new ArrayList<BlockPatch>();
            for (var component : lowered) {
                var compilation = (FbdCompiler.Compilation) component.params().get(FbdPlanLowering.COMPILED);
                var p = component.relativePosition();
                var blocks = new MetaAssemblyEngine().executeGeometry(FbdAssemblyAdapter.spec(compilation),
                        new MetaAssemblyEngine.Context(null, new BlockPos(p.x(),p.y(),p.z()), Direction.NORTH, null,
                                new FbdExecutionBudget(FbdLimits.DEFAULT)));
                var patches = FbdAssemblyAdapter.finalPatches(blocks);
                assertFalse(patches.isEmpty());
                assertEquals(patches.size(), patches.stream().map(b -> new BlockPos(b.dx(),b.dy(),b.dz())).distinct().count());
                var conflict = occupancy.accept(component, patches);
                assertTrue(conflict.isEmpty(), conflict::toString);
                all.addAll(patches);
            }
            assertTrue(PatchConflictValidator.inspectAuthorized(all, occupancy).isEmpty());
        }
    }

    @Test void programFinalizationKeepsLastStateIncludingRemovalAndDoesNotAuthorizeOtherPrograms() {
        var pos = BlockPos.ORIGIN;
        var sequence = List.of(new PlannedBlock(pos, Blocks.STONE.getDefaultState()),
                new PlannedBlock(pos, Blocks.AIR.getDefaultState()));
        var patches = FbdAssemblyAdapter.finalPatches(sequence);
        assertEquals(1, patches.size());
        assertEquals(BlockPatch.REMOVE, patches.getFirst().action());
        var occupancy = new PatchOccupancyIndex();
        var first = component("ASSEMBLY", Map.of("component_id", "a"));
        var second = component("ASSEMBLY", Map.of("component_id", "b"));
        assertTrue(occupancy.accept(first, FbdAssemblyAdapter.finalPatches(List.of(new PlannedBlock(pos, Blocks.STONE.getDefaultState())))).isEmpty());
        assertTrue(occupancy.validate(second, FbdAssemblyAdapter.finalPatches(List.of(new PlannedBlock(pos, Blocks.GLASS.getDefaultState())))).isPresent());
        assertTrue(occupancy.validate(second, patches).isPresent());
    }

    @Test void actualWriteAndSamplingLoopsAbortWithTheOriginatingNode() {
        var compiled = FbdCompiler.compile(program(shell("shell")));
        for (var limits : List.of(new FbdLimits(65536, 2000, 24, 200000, 5000, 20000, 2000000, 5),
                new FbdLimits(65536, 2000, 24, 200000, 5000, 20000, 5, 200000))) {
            var error = assertThrows(FbdException.class, () -> new MetaAssemblyEngine().executeGeometry(
                    FbdAssemblyAdapter.spec(compiled), new MetaAssemblyEngine.Context(null, BlockPos.ORIGIN, Direction.SOUTH, null,
                            new FbdExecutionBudget(limits))));
            assertEquals("budget_exceeded", error.code);
            assertEquals("shell", error.node);
            assertEquals("/main/0", error.path);
        }
    }

    @Test void authorityConflictsAndInvalidLaterProgramsReturnNoLoweredSubset() {
        var p = program(shell("shell"));
        for (String key : List.of("assembly", "ops", "graph", "macro", "preset", "skeleton")) {
            AssemblyCompileDiagnostics.clear();
            assertTrue(FbdPlanLowering.lower(List.of(component("ASSEMBLY", Map.of("fbd_program", p, key, Map.of())))).isEmpty());
            assertEquals("E_FBD_AMBIGUOUS_AUTHORITY", AssemblyCompileDiagnostics.get().code());
        }
        AssemblyCompileDiagnostics.clear();
        var bad = component("ASSEMBLY", Map.of("fbd_program", program(Map.of("stmt", "call", "id", "bad", "fn", "missing", "args", Map.of()))));
        var otherSlot = new Component(bad.componentType(), "other", bad.relativePosition(), bad.dimensions(), bad.features(), bad.params());
        assertTrue(FbdPlanLowering.lower(List.of(component("ASSEMBLY", Map.of("fbd_program", p)),
                otherSlot)).isEmpty());
        assertEquals("E_FBD_UNKNOWN_FUNCTION", AssemblyCompileDiagnostics.get().code());
        assertEquals("bad", AssemblyCompileDiagnostics.get().details().get("node"));
        assertTrue(AssemblyCompileDiagnostics.get().path().startsWith("/components/1/params/fbd_program/"));
    }

    @Test void proceduralAuthorityDoesNotSilentlyStripAnOrdinaryBodyFromTheSameSlot() {
        assertTrue(FbdPlanLowering.lower(List.of(component("ASSEMBLY", Map.of("fbd_program", program(shell("a")))),
                component("MASS_MAIN", Map.of()))).isEmpty());
        assertEquals("E_FBD_AMBIGUOUS_AUTHORITY", AssemblyCompileDiagnostics.get().code());
    }

    @Test void multipleSmallProgramsCannotBypassThePlanGeometryBudget() {
        var components = new ArrayList<Component>();
        for (int i = 0; i < 3; i++) components.add(new Component("ASSEMBLY", "slot" + i, new Vec3i(0,0,0),
                new Dimensions(35,35,25), List.of(), Map.of("fbd_program", program(Map.of("stmt", "emit", "id", "body", "op", "SHELL_BOX",
                    "params", Map.of("w",35,"d",35,"h",24))))));
        assertTrue(FbdPlanLowering.lower(components).isEmpty());
        assertEquals("E_FBD_BUDGET_EXCEEDED", AssemblyCompileDiagnostics.get().code());
    }

    @Test void realPlanCompilerRejectsBadProgramBeforeAnyWorldOrGeneratorIsNeeded() {
        var c = component("ASSEMBLY", Map.of("fbd_program", program(Map.of("stmt", "call", "id", "bad", "fn", "missing", "args", Map.of()))));
        var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).anchor(new Vec3i(0,64,0))
                .styleProfile("DEFAULT").components(List.of(c)).build();
        assertTrue(ComponentPlanCompiler.compile(plan).isEmpty());
        assertEquals("E_FBD_UNKNOWN_FUNCTION", AssemblyCompileDiagnostics.get().code());
    }
}
