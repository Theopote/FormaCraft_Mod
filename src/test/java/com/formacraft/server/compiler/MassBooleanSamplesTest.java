package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.impl.MassMainGenerator;
import com.formacraft.common.generation.component.util.MassBooleanComposition;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import com.formacraft.test.PatchTestSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 02.2 Task E — boolean mass samples (compile + generator).
 */
class MassBooleanSamplesTest {

    @BeforeAll
    static void boot() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @BeforeEach
    @AfterEach
    void clearDiag() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void s01_nestedAtriumEmitHasNoCourtCenter() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "atrium");
        params.put("anchor_mode", "min_corner");
        params.put("solid", true);
        params.put("masses", List.of(
                Map.of("operation", "add",
                        "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 10, "depth", 10, "height", 5)),
                Map.of("operation", "subtract",
                        "offset", Map.of("x", 2, "y", 0, "z", 2),
                        "dimensions", Map.of("width", 6, "depth", 6, "height", 5))
        ));
        Component c = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(10, 10, 5), List.of(), params);
        var patches = new MassMainGenerator().generate(new SemanticComponent("MASS_MAIN", null, c));
        assertFalse(patches.isEmpty());
        var blocks = PatchTestSnapshot.blocks(patches);
        assertNull(blocks.get(new Vec3i(5, 1, 5)), "subtracted court must stay empty");
        assertNotNull(blocks.get(new Vec3i(0, 0, 0)));
    }

    @Test
    void s03_unionEccentricWingsViaMasses() {
        Map<String, Object> params = new HashMap<>();
        params.put("anchor_mode", "min_corner");
        params.put("solid", true);
        params.put("masses", List.of(
                Map.of("operation", "add",
                        "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 8, "depth", 6, "height", 4)),
                Map.of("operation", "add",
                        "offset", Map.of("x", 8, "y", 0, "z", 1),
                        "dimensions", Map.of("width", 4, "depth", 4, "height", 4))
        ));
        // needsCsg is false when only ADD — still verify additive multi-mass path unchanged.
        Component c = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(12, 6, 4), List.of(), params);
        assertFalse(MassBooleanComposition.needsCsg(c));
        // Force CSG union path with an explicit no-op subtract of empty? Use intersect of union chain:
        // Instead stamp a trivial carve outside to force CSG while keeping wing solid.
        params.put("masses", List.of(
                Map.of("operation", "add",
                        "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 8, "depth", 6, "height", 4)),
                Map.of("operation", "add",
                        "offset", Map.of("x", 8, "y", 0, "z", 1),
                        "dimensions", Map.of("width", 4, "depth", 4, "height", 4)),
                Map.of("operation", "subtract",
                        "offset", Map.of("x", 20, "y", 0, "z", 20),
                        "dimensions", Map.of("width", 1, "depth", 1, "height", 1))
        ));
        c = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(12, 6, 4), List.of(), params);
        assertTrue(MassBooleanComposition.needsCsg(c));
        var patches = new MassMainGenerator().generate(new SemanticComponent("MASS_MAIN", null, c));
        var blocks = PatchTestSnapshot.blocks(patches);
        assertNotNull(blocks.get(new Vec3i(0, 0, 0)));
        assertNotNull(blocks.get(new Vec3i(9, 0, 2)), "eccentric wing present");
    }

    @Test
    void s04_intentionalPeerInterlockCompilesWithoutFootprintConflict() {
        Component host = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(10, 10, 6), List.of(),
                Map.of("component_id", "volume_a", "anchor_mode", "min_corner", "solid", true));
        Component peer = new Component("MASS_MAIN", "main", new Vec3i(4, 0, 4),
                new Dimensions(10, 10, 6), List.of(),
                Map.of("component_id", "volume_b", "anchor_mode", "min_corner", "solid", true,
                        "host_id", "volume_a", "intentional_interlock", true,
                        "mass_operation", "union"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .components(List.of(host, peer))
                .build();
        var patches = ComponentPlanCompiler.compile(plan);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());
    }

    @Test
    void s05_missingHostEmitsGap() {
        Component sat = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(4, 4, 4), List.of(),
                Map.of("component_id", "orphan_carve", "anchor_mode", "min_corner",
                        "mass_operation", "subtract"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .components(List.of(sat))
                .build();
        var patches = ComponentPlanCompiler.compile(plan);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_MASS_CSG_HOST_MISSING", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void s06_foldedSatelliteDoesNotSelfEmit() {
        Component host = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 5), List.of(),
                Map.of("component_id", "host", "anchor_mode", "min_corner", "solid", true));
        Component carve = new Component("MASS_MAIN", "main", new Vec3i(2, 0, 2),
                new Dimensions(4, 4, 5), List.of(),
                Map.of("component_id", "carve", "anchor_mode", "min_corner",
                        "host_id", "host", "mass_operation", "subtract"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .components(List.of(host, carve))
                .build();
        var patches = ComponentPlanCompiler.compile(plan);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());
        var blocks = PatchTestSnapshot.blocks(patches);
        assertNull(blocks.get(new Vec3i(3, 1, 3)), "host-local carve must remove court");
        assertNotNull(blocks.get(new Vec3i(0, 0, 0)));
    }
}
