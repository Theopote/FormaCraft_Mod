package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.CompileCapabilityDiagnostics;
import com.formacraft.common.generation.component.impl.MassMainGenerator;
import com.formacraft.common.generation.component.util.BuildingGeometryContext;
import com.formacraft.common.generation.component.util.MassBooleanComposition;
import com.formacraft.common.geometry.shape.CsgOp;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 02.3.1 — building shell samples W01–W12 + performance gates.
 */
class MassShell0231Test {

    @BeforeAll
    static void boot() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @BeforeEach
    @AfterEach
    void clearDiag() {
        AssemblyCompileDiagnostics.clear();
        CompileCapabilityDiagnostics.clear();
    }

    private static Component boxMass(String id, int w, int d, int h, Map<String, Object> extra) {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", id);
        params.put("anchor_mode", "min_corner");
        params.putAll(extra);
        return new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(w, d, h), List.of(), params);
    }

    private static Map<Vec3i, String> generate(Component c) {
        var patches = new MassMainGenerator().generate(new SemanticComponent("MASS_MAIN", null, c));
        return PatchTestSnapshot.blocks(patches);
    }

    private static Map<String, Object> csgMasses(List<Map<String, Object>> masses) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("masses", masses);
        return extra;
    }

    @Test
    void w01_rectHollowHasWallsAndEmptyCore() {
        Map<String, Object> extra = csgMasses(List.of(
                Map.of("operation", "add",
                        "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 10, "depth", 8, "height", 5))
        ));
        extra.put("hollow", true);
        extra.put("wall_thickness", 1);
        // Force CSG path via no-op subtract outside.
        extra.put("masses", List.of(
                Map.of("operation", "add",
                        "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 10, "depth", 8, "height", 5)),
                Map.of("operation", "subtract",
                        "offset", Map.of("x", 40, "y", 0, "z", 40),
                        "dimensions", Map.of("width", 1, "depth", 1, "height", 1))
        ));
        Component c = boxMass("hollow_rect", 10, 8, 5, extra);
        var blocks = generate(c);
        assertNotNull(blocks.get(new Vec3i(0, 1, 0)));
        assertNull(blocks.get(new Vec3i(4, 1, 3)), "interior eroded");
        assertFalse(CompileCapabilityDiagnostics.hasGap());
    }

    @Test
    void w02_thickerWallLeavesSmallerInterior() {
        Map<String, Object> thin = new HashMap<>();
        thin.put("hollow", true);
        thin.put("wall_thickness", 1);
        thin.put("masses", List.of(
                Map.of("operation", "add",
                        "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 12, "depth", 12, "height", 4)),
                Map.of("operation", "subtract",
                        "offset", Map.of("x", 50, "y", 0, "z", 50),
                        "dimensions", Map.of("width", 1, "depth", 1, "height", 1))
        ));
        Map<String, Object> thick = new HashMap<>(thin);
        thick.put("wall_thickness", 2);
        var thinBlocks = generate(boxMass("thin", 12, 12, 4, thin));
        var thickBlocks = generate(boxMass("thick", 12, 12, 4, thick));
        assertTrue(thinBlocks.size() < thickBlocks.size()
                        || thinBlocks.containsKey(new Vec3i(2, 1, 2)),
                "thicker wall places more shell or deeper inset");
        assertNull(thickBlocks.get(new Vec3i(5, 1, 5)));
        assertNotNull(thickBlocks.get(new Vec3i(0, 0, 0)));
    }

    @Test
    void w03_solidTrueFillsDomain() {
        Map<String, Object> extra = new HashMap<>();
        extra.put("solid", true);
        extra.put("masses", List.of(
                Map.of("operation", "add",
                        "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 6, "depth", 6, "height", 3)),
                Map.of("operation", "subtract",
                        "offset", Map.of("x", 40, "y", 0, "z", 40),
                        "dimensions", Map.of("width", 1, "depth", 1, "height", 1))
        ));
        var blocks = generate(boxMass("solid_box", 6, 6, 3, extra));
        assertNotNull(blocks.get(new Vec3i(3, 1, 3)), "solid keeps interior");
        assertEquals(6 * 6 * 3, blocks.size());
    }

    @Test
    void w08_subtractAtriumHollowShell() {
        Map<String, Object> extra = new HashMap<>();
        extra.put("hollow", true);
        extra.put("wall_thickness", 1);
        extra.put("masses", List.of(
                Map.of("operation", "add",
                        "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 12, "depth", 12, "height", 5)),
                Map.of("operation", "subtract",
                        "offset", Map.of("x", 3, "y", 0, "z", 3),
                        "dimensions", Map.of("width", 6, "depth", 6, "height", 5))
        ));
        var blocks = generate(boxMass("atrium", 12, 12, 5, extra));
        assertNull(blocks.get(new Vec3i(5, 1, 5)), "court empty");
        assertNotNull(blocks.get(new Vec3i(0, 0, 0)));
        // Inner face of the remaining ring (court starts at x=z=3).
        assertTrue(
                blocks.containsKey(new Vec3i(2, 0, 2))
                        || blocks.containsKey(new Vec3i(2, 1, 3))
                        || blocks.containsKey(new Vec3i(3, 1, 2)),
                "ring wall near court must remain");
    }

    @Test
    void w09_intersectKeepsOverlapShell() {
        Map<String, Object> extra = new HashMap<>();
        extra.put("solid", true);
        extra.put("masses", List.of(
                Map.of("operation", "add",
                        "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 8, "depth", 8, "height", 4)),
                Map.of("operation", "intersect",
                        "offset", Map.of("x", 4, "y", 0, "z", 4),
                        "dimensions", Map.of("width", 8, "depth", 8, "height", 4))
        ));
        var blocks = generate(boxMass("cut", 12, 12, 4, extra));
        assertNull(blocks.get(new Vec3i(0, 0, 0)));
        assertNotNull(blocks.get(new Vec3i(4, 0, 4)));
        assertNull(blocks.get(new Vec3i(9, 0, 9)));
    }

    @Test
    void w11_thicknessCollapseEmitsGap() {
        Map<String, Object> extra = new HashMap<>();
        extra.put("hollow", true);
        extra.put("wall_thickness", 8);
        extra.put("masses", List.of(
                Map.of("operation", "add",
                        "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 4, "depth", 4, "height", 3)),
                Map.of("operation", "subtract",
                        "offset", Map.of("x", 40, "y", 0, "z", 40),
                        "dimensions", Map.of("width", 1, "depth", 1, "height", 1))
        ));
        var patches = new MassMainGenerator().generate(
                new SemanticComponent("MASS_MAIN", null, boxMass("narrow", 4, 4, 3, extra)));
        assertTrue(patches.isEmpty());
        assertTrue(CompileCapabilityDiagnostics.hasGap());
        assertEquals("E_MASS_WALL_THICKNESS", CompileCapabilityDiagnostics.get().code());
    }

    @Test
    void w12_legalEmptyCsg() {
        Map<String, Object> extra = new HashMap<>();
        extra.put("solid", true);
        extra.put("masses", List.of(
                Map.of("operation", "add",
                        "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 3, "depth", 3, "height", 3)),
                Map.of("operation", "subtract",
                        "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 3, "depth", 3, "height", 3))
        ));
        var patches = new MassMainGenerator().generate(
                new SemanticComponent("MASS_MAIN", null, boxMass("gone", 3, 3, 3, extra)));
        assertTrue(patches.isEmpty());
        assertFalse(CompileCapabilityDiagnostics.hasGap());
    }

    @Test
    void domainCachedOnceInGeometryContext() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "cached");
        params.put("anchor_mode", "min_corner");
        params.put("masses", List.of(
                Map.of("operation", "add",
                        "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 5, "depth", 5, "height", 3)),
                Map.of("operation", "subtract",
                        "offset", Map.of("x", 1, "y", 0, "z", 1),
                        "dimensions", Map.of("width", 2, "depth", 2, "height", 3))
        ));
        Component c = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(5, 5, 3), List.of(), params);
        try (var scope = BuildingGeometryContext.open()) {
            BuildingGeometryContext.putMass(c);
            var entry = BuildingGeometryContext.get("cached");
            assertNotNull(entry);
            assertNotNull(entry.domain());
            assertSame(entry.domain(), BuildingGeometryContext.domainOrCompose(c));
        }
    }

    @Test
    void f_moverOnlyAuthDoesNotMoveUnauthorizedPeer() {
        Component a = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 6), List.of(),
                Map.of("component_id", "primary_a", "anchor_mode", "min_corner",
                        "allow_mass_separation", true));
        Component b = new Component("MASS_MAIN", "main", new Vec3i(4, 0, 4),
                new Dimensions(8, 8, 6), List.of(),
                Map.of("component_id", "primary_b", "anchor_mode", "min_corner"));
        Component c = new Component("MASS_MAIN", "main", new Vec3i(30, 0, 0),
                new Dimensions(8, 8, 6), List.of(),
                Map.of("component_id", "locked_c", "anchor_mode", "min_corner"));
        Component d = new Component("MASS_MAIN", "main", new Vec3i(34, 0, 4),
                new Dimensions(8, 8, 6), List.of(),
                Map.of("component_id", "locked_d", "anchor_mode", "min_corner"));
        LlmPlan repaired = MassFootprintSeparationRepair.apply(
                LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                        .components(List.of(a, b, c, d)).build());
        // A–B: earlier id primary_a holds auth → separates. C–D: no auth → gap, positions kept.
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_MASS_FOOTPRINT_CONFLICT", AssemblyCompileDiagnostics.get().code());
        Vec3i cPos = repaired.components().stream()
                .filter(x -> "locked_c".equals(String.valueOf(x.params().get("component_id"))))
                .findFirst().orElseThrow().relativePosition();
        Vec3i dPos = repaired.components().stream()
                .filter(x -> "locked_d".equals(String.valueOf(x.params().get("component_id"))))
                .findFirst().orElseThrow().relativePosition();
        assertEquals(new Vec3i(30, 0, 0), cPos);
        assertEquals(new Vec3i(34, 0, 4), dPos);
    }

    @Test
    void perf_boundaryOverSolidsIsLinearInVoxelCount() {
        var result = MassBooleanComposition.compose(List.of(
                new MassBooleanComposition.CsgPart(CsgOp.UNION, 0, 0, 0, 20, 20, 20)
        ));
        int n = result.voxels().size();
        long t0 = System.nanoTime();
        int surface = 0;
        for (var v : result.voxels()) {
            if (result.isSolidSurface(v.x(), v.y(), v.z())) surface++;
        }
        long elapsed = System.nanoTime() - t0;
        assertTrue(surface > 0);
        // Soft bound: indexed path should finish a 8k solid well under 200ms on CI.
        assertTrue(elapsed < 200_000_000L, "boundary pass too slow: " + elapsed + "ns for n=" + n);
        assertEquals(20 * 20 * 20, n);
    }

    @Test
    void horizontalBoundaryAtMarksEdges() {
        var result = MassBooleanComposition.compose(List.of(
                new MassBooleanComposition.CsgPart(CsgOp.UNION, 0, 0, 0, 5, 5, 2)
        ));
        Set<Long> edge = result.horizontalBoundaryAt(0);
        assertFalse(edge.isEmpty());
        assertTrue(edge.contains(MassBooleanComposition.pack2(0, 0)));
        assertFalse(edge.contains(MassBooleanComposition.pack2(2, 2)));
    }
}
