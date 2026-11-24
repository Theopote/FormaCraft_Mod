package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.MassMainGenerator;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LlmPlan multi-building / site trunk: two building_id masses + PATH from/to;
 * smart emit skips without world (polyline fallback via registry).
 */
class LlmPlanSiteTrunkTest {
    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @BeforeEach
    void clearDiagnostics() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void twoBuildingIdsCompileDistinctMassFootprints() {
        Component a = mass("pavilion_a", new Vec3i(0, 0, 0));
        Component b = mass("pavilion_b", new Vec3i(18, 0, 0));
        Map<String, Object> pathParams = new HashMap<>();
        pathParams.put("component_id", "approach");
        pathParams.put("from", Map.of("x", 3, "y", 0, "z", 0));
        pathParams.put("to", Map.of("x", 15, "y", 0, "z", 0));
        pathParams.put("width", 3);
        pathParams.put("path_block", "minecraft:gravel");
        Component path = new Component(
                "PATH", null, new Vec3i(0, 0, 0), new Dimensions(3, 3, 1), List.of(), pathParams);

        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .styleProfile("DEFAULT")
                .anchor(new Vec3i(0, 64, 0))
                .allowCompilerSatellites(true)
                .components(List.of(a, b, path))
                .build();

        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan);
        assertFalse(patches.isEmpty());
        assertNull(AssemblyCompileDiagnostics.get(),
                () -> "unexpected gap: " + AssemblyCompileDiagnostics.get());

        Set<String> types = plan.components().stream()
                .map(Component::componentType)
                .collect(Collectors.toSet());
        assertTrue(types.contains("MASS_MAIN"));
        assertTrue(types.contains("PATH"));

        // Both masses still present after separation / emit (not collapsed to one house).
        long massCount = plan.components().stream()
                .filter(c -> "MASS_MAIN".equals(ComponentPlanTypes.normalizeType(c.componentType())))
                .count();
        assertEquals(2, massCount);

        var left = new MassMainGenerator().generate(new SemanticComponent("MASS_MAIN", null, a, "DEFAULT"));
        var right = new MassMainGenerator().generate(new SemanticComponent("MASS_MAIN", null, b, "DEFAULT"));
        assertFalse(left.isEmpty());
        assertFalse(right.isEmpty());
        int leftMaxX = left.stream().mapToInt(BlockPatch::dx).max().orElse(0);
        int rightMinX = right.stream().mapToInt(BlockPatch::dx).min().orElse(0);
        assertTrue(rightMinX > leftMaxX, "masses should not collapse into one footprint");
    }

    @Test
    void smartEmitWithoutWorldFallsBackToEmptyOptional() {
        Map<String, Object> params = new HashMap<>();
        params.put("from", Map.of("x", 0, "y", 0, "z", 0));
        params.put("to", Map.of("x", 8, "y", 0, "z", 0));
        params.put("width", 3);
        Component path = new Component(
                "PATH", null, new Vec3i(0, 0, 0), new Dimensions(3, 3, 1), List.of(), params);
        SemanticComponent semantic = new SemanticComponent("PATH", null, path, "DEFAULT");
        Optional<List<BlockPatch>> smart = LlmPlanPathSmartEmit.tryEmit(
                semantic, null, null, new Vec3i(0, 0, 0));
        assertTrue(smart.isEmpty());
    }

    @Test
    void smartEmitSkipsWithoutEndpoints() {
        Component path = new Component(
                "PATH", null, new Vec3i(0, 0, 0), new Dimensions(4, 4, 1), List.of(),
                Map.of("path_block", "minecraft:gravel"));
        SemanticComponent semantic = new SemanticComponent("PATH", null, path, "DEFAULT");
        // world/anchor non-null would be needed for A*; without endpoints still empty.
        Optional<List<BlockPatch>> smart = LlmPlanPathSmartEmit.tryEmit(
                semantic, null, null, new Vec3i(0, 0, 0));
        assertTrue(smart.isEmpty());
    }

    private static Component mass(String buildingId, Vec3i pos) {
        Map<String, Object> params = new HashMap<>();
        params.put("building_id", buildingId);
        params.put("component_id", buildingId);
        params.put("wall_thickness", 1);
        return new Component("MASS_MAIN", null, pos, new Dimensions(6, 6, 5), List.of(), params);
    }
}
