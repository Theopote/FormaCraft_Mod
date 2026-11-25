package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.routing.GenerationAuthority;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.server.generation.GenerationCompileContext;
import com.formacraft.server.generation.GenerationHub;
import com.formacraft.server.generation.component.adaptor.ComponentPipelineStepId;
import com.formacraft.server.generation.component.adaptor.RoutedGenerationResult;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.block.StairsBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 01.6: path smart-emit must stay under GenerationHub authority —
 * no CAPTURED_LIBRARY override, BlockState properties preserved, hard fails surface.
 */
class PathSmartEmitAuthorityTest {
    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @BeforeEach
    void clearDiagnostics() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void serializeStatePreservesStairFacing() {
        var state = Blocks.OAK_STAIRS.getDefaultState()
                .with(StairsBlock.FACING, Direction.EAST);
        String encoded = LlmPlanPathSmartEmit.serializeState(state);
        assertTrue(encoded.startsWith("minecraft:oak_stairs"), encoded);
        assertTrue(encoded.contains("facing=east"), encoded);
        assertNotEquals("minecraft:oak_stairs", encoded, "must keep BlockState properties");
    }

    @Test
    void capturedLibraryPathWithExplicitRequestNeverUsesSmartEmitStep() {
        Map<String, Object> params = new HashMap<>();
        params.put("generation_authority", "CAPTURED_LIBRARY");
        params.put("from", Map.of("x", 0, "y", 0, "z", 0));
        params.put("to", Map.of("x", 8, "y", 0, "z", 0));
        params.put("width", 3);
        params.put("path_block", "minecraft:gravel");
        Component path = new Component(
                "PATH",
                null,
                new Vec3i(0, 0, 0),
                new Dimensions(3, 3, 1),
                List.of("component_request:{\"component_id\":\"missing_path_lib\"}"),
                params);
        SemanticComponent semantic = new SemanticComponent("PATH", null, path, "DEFAULT");
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .components(List.of(path))
                .build();

        // Pass a non-null anchor so a pre-hub bypass *could* have short-circuited; hub must still
        // honor CAPTURED_LIBRARY first (library miss → native registry, never PATH_SMART_EMIT).
        RoutedGenerationResult result = GenerationCompileContext.runWith(
                GenerationCompileContext.fromPlan(plan, false),
                () -> GenerationHub.generateComponentResult(semantic, null, new BlockPos(0, 64, 0)));

        assertNotEquals(ComponentPipelineStepId.PATH_SMART_EMIT, result.pipelineStep(),
                "explicit library request must not be overridden by path smart-emit");
        // Optional library miss demotes to native COMPONENTS registry — still not smart-emit.
        assertEquals(ComponentPipelineStepId.COMPONENT_REGISTRY, result.pipelineStep());
        assertEquals(GenerationAuthority.COMPONENTS, result.authority());
        assertTrue(result.commitAllowed() && !result.patches().isEmpty(),
                "native registry polyline must remain available after optional library miss");
    }

    @Test
    void componentsPathWithoutWorldFallsThroughRegistryNotSmartStep() {
        Map<String, Object> params = new HashMap<>();
        params.put("from", Map.of("x", 0, "y", 0, "z", 0));
        params.put("to", Map.of("x", 8, "y", 0, "z", 0));
        params.put("width", 3);
        params.put("path_block", "minecraft:gravel");
        Component path = new Component(
                "PATH", null, new Vec3i(0, 0, 0), new Dimensions(3, 3, 1), List.of(), params);
        SemanticComponent semantic = new SemanticComponent("PATH", null, path, "DEFAULT");
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .components(List.of(path))
                .build();

        RoutedGenerationResult result = GenerationCompileContext.runWith(
                GenerationCompileContext.fromPlan(plan, false),
                () -> GenerationHub.generateComponentResult(semantic, null, new BlockPos(0, 64, 0)));

        assertNotEquals(ComponentPipelineStepId.PATH_SMART_EMIT, result.pipelineStep());
        // Without world, smart emit is ineligible; registry polyline may still succeed.
        if (result.commitAllowed()) {
            assertFalse(result.patches().isEmpty());
            assertNull(result.pipelineStep() == ComponentPipelineStepId.PATH_SMART_EMIT
                    ? "smart" : null);
        }
    }

    @Test
    void emitPassRoutesPathThroughHubNotPreBypass() {
        // Compile-time regression: PATH with endpoints and no world still compiles via hub registry.
        Map<String, Object> pathParams = new HashMap<>();
        pathParams.put("component_id", "approach");
        pathParams.put("from", Map.of("x", 0, "y", 0, "z", 0));
        pathParams.put("to", Map.of("x", 10, "y", 0, "z", 0));
        pathParams.put("width", 3);
        pathParams.put("path_block", "minecraft:gravel");
        Component path = new Component(
                "PATH", null, new Vec3i(0, 0, 0), new Dimensions(3, 3, 1), List.of(), pathParams);

        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .styleProfile("DEFAULT")
                .anchor(new Vec3i(0, 64, 0))
                .allowCompilerSatellites(true)
                .components(List.of(path))
                .build();

        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan);
        assertNull(AssemblyCompileDiagnostics.get(),
                () -> "unexpected gap: " + AssemblyCompileDiagnostics.get());
        assertFalse(patches.isEmpty(), "PATH must emit via GenerationHub registry polyline when world-less");
    }
}
