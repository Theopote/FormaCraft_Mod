package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.MassMainGenerator;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.generation.component.util.MassBooleanComposition;
import com.formacraft.common.generation.component.util.MassSpatialRelation;
import com.formacraft.common.geometry.shape.CsgOp;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Slot;
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
 * Phase 02.2.1 — mass boolean geometry hardening gates (Tasks A–F compile-through).
 */
class MassHarden0221Test {

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
    void a01_intersectEmptyAfterSeedStaysEmpty() {
        // (A ∩ B)=∅ then ∩ C must stay empty — never reseed from C (P0-01).
        var result = MassBooleanComposition.compose(List.of(
                new MassBooleanComposition.CsgPart(CsgOp.UNION, 0, 0, 0, 4, 4, 4),
                new MassBooleanComposition.CsgPart(CsgOp.INTERSECT, 10, 0, 10, 4, 4, 4),
                new MassBooleanComposition.CsgPart(CsgOp.INTERSECT, 0, 0, 0, 4, 4, 4)
        ));
        assertTrue(result.isEmpty());
        assertFalse(result.contains(1, 1, 1));
    }

    @Test
    void a02_subtractThenUnionYieldsOperand() {
        // (A − A) ∪ B → B
        var result = MassBooleanComposition.compose(List.of(
                new MassBooleanComposition.CsgPart(CsgOp.UNION, 0, 0, 0, 4, 4, 4),
                new MassBooleanComposition.CsgPart(CsgOp.SUBTRACT, 0, 0, 0, 4, 4, 4),
                new MassBooleanComposition.CsgPart(CsgOp.UNION, 2, 0, 2, 3, 3, 3)
        ));
        assertFalse(result.isEmpty());
        assertTrue(result.contains(2, 0, 2));
        assertFalse(result.contains(0, 0, 0));
    }

    @Test
    void a03_illegalOpThrowsUnsupported() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> MassBooleanComposition.parseOpStrict("xor"));
        assertTrue(ex.getMessage().startsWith("E_MASS_CSG_OP_UNSUPPORTED"));
    }

    @Test
    void a04_selfSubtractIsLegalEmpty() {
        var result = MassBooleanComposition.compose(List.of(
                new MassBooleanComposition.CsgPart(CsgOp.UNION, 0, 0, 0, 3, 3, 3),
                new MassBooleanComposition.CsgPart(CsgOp.SUBTRACT, 0, 0, 0, 3, 3, 3)
        ));
        assertTrue(result.isEmpty());
        assertEquals(0, result.envelopeWidth());
    }

    @Test
    void a05_selfIntersectKeepsSolid() {
        var result = MassBooleanComposition.compose(List.of(
                new MassBooleanComposition.CsgPart(CsgOp.UNION, 0, 0, 0, 4, 4, 4),
                new MassBooleanComposition.CsgPart(CsgOp.INTERSECT, 0, 0, 0, 4, 4, 4)
        ));
        assertEquals(4 * 4 * 4, result.voxels().size());
    }

    @Test
    void b01_crossSlotFoldUsesPlanOrigin() {
        // Host at slot main (0,0,0); satellite at slot wing with slot anchor x=30.
        Component host = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 5), List.of(),
                Map.of("component_id", "host", "anchor_mode", "min_corner", "solid", true));
        Component carve = new Component("MASS_MAIN", "wing", new Vec3i(0, 0, 0),
                new Dimensions(4, 4, 5), List.of(),
                Map.of("component_id", "carve", "anchor_mode", "min_corner",
                        "host_id", "host", "mass_operation", "subtract"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .layout(new Layout(null, false, List.of(
                        new Slot("main", new Vec3i(0, 0, 0), null, null, null, null),
                        new Slot("wing", new Vec3i(30, 0, 0), null, null, null, null))))
                .components(List.of(host, carve))
                .build();
        LlmPlan folded = MassCsgPeerFoldRepair.apply(plan);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        Component foldedHost = folded.components().get(0);
        assertTrue(MassBooleanComposition.needsCsg(foldedHost));
        Object masses = foldedHost.params().get("masses");
        assertInstanceOf(List.class, masses);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) masses;
        assertTrue(list.size() >= 2);
        Map<String, Object> child = list.get(list.size() - 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> offset = (Map<String, Object>) child.get("offset");
        assertEquals(30, ((Number) offset.get("x")).intValue(), "plan-space Δx via originInPlan");
    }

    @Test
    void c01_csgResultBoundsAndVoids() {
        Map<String, Object> params = new HashMap<>();
        params.put("anchor_mode", "min_corner");
        params.put("masses", List.of(
                Map.of("operation", "add",
                        "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 6, "depth", 6, "height", 4)),
                Map.of("operation", "subtract",
                        "offset", Map.of("x", 2, "y", 0, "z", 2),
                        "dimensions", Map.of("width", 2, "depth", 2, "height", 4))
        ));
        Component c = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(6, 6, 4), List.of(), params);
        var result = MassBooleanComposition.composeFromComponent(c);
        assertFalse(result.isEmpty());
        assertEquals(0, result.minX());
        assertEquals(6, result.maxX());
        assertTrue(result.contains(0, 0, 0));
        assertFalse(result.contains(2, 1, 2));
        assertFalse(result.voids().isEmpty());
        assertFalse(result.footprintAt(0).isEmpty());
        assertNotNull(result.bounds());
    }

    @Test
    void c02_atriumCompileCourtEmptyAndNoGap() {
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
        var patches = ComponentPlanCompiler.compile(LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .components(List.of(c))
                .build());
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        var blocks = PatchTestSnapshot.blocks(patches);
        assertNull(blocks.get(new Vec3i(5, 1, 5)));
        assertNotNull(blocks.get(new Vec3i(0, 0, 0)));
    }

    @Test
    void d01_nestedThinVolumeIsAabb3NotSeam() {
        // Thin shaft nearly contained in hall — min(overlap)≤2 but containment → AABB3 (P1-01).
        var hall = new ComponentFootprintUtil.Bounds(0, 0, 0, 20, 10, 20);
        var shaft = new ComponentFootprintUtil.Bounds(5, 0, 5, 7, 10, 7);
        assertEquals(MassSpatialRelation.AABB3_INTERSECT,
                MassSpatialRelation.classify(hall, shaft, false));
    }

    @Test
    void d02_connectionSeamWithoutOptInGaps() {
        Component hall = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(13, 11, 9), List.of(),
                Map.of("component_id", "keep_hall", "anchor_mode", "min_corner"));
        // 1-cell X penetration → CONNECTION_SEAM; not soft-annex named.
        Component tower = new Component("MASS_MAIN", "main", new Vec3i(12, 0, 1),
                new Dimensions(8, 8, 16), List.of(),
                Map.of("component_id", "keep_tower", "anchor_mode", "min_corner"));
        LlmPlan repaired = MassFootprintSeparationRepair.apply(
                LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                        .components(List.of(hall, tower)).build());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_MASS_FOOTPRINT_CONFLICT", AssemblyCompileDiagnostics.get().code());
        assertEquals(hall.relativePosition(), repaired.components().get(0).relativePosition());
        assertEquals(tower.relativePosition(), repaired.components().get(1).relativePosition());
    }

    @Test
    void d02b_compoundPrimarySeamAuthorizesCollapse() {
        // Authored COMPOUND skeleton + peer MASS_MAIN: GEOMETRY seam-collapse without opt-in
        // (oct17_retry_2 staggered lodge). Non-COMPOUND still gaps — see d02.
        Component hall = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(13, 11, 9), List.of(),
                Map.of("component_id", "lodge_a", "anchor_mode", "min_corner"));
        Component wing = new Component("MASS_MAIN", "main", new Vec3i(12, 0, 1),
                new Dimensions(8, 8, 16), List.of(),
                Map.of("component_id", "lodge_b", "anchor_mode", "min_corner"));
        LlmPlan repaired = MassFootprintSeparationRepair.apply(
                LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                        .layout(new Layout(Layout.SkeletonType.COMPOUND, false, List.of()))
                        .components(List.of(hall, wing)).build());
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        boolean moved = !hall.relativePosition().equals(repaired.components().get(0).relativePosition())
                || !wing.relativePosition().equals(repaired.components().get(1).relativePosition());
        assertTrue(moved, "COMPOUND primary CONNECTION_SEAM should seam-collapse to contact");
    }

    @Test
    void d03_localSeparationOptInDoesNotDiffuse() {
        Component a = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 6), List.of(),
                Map.of("component_id", "primary_a", "anchor_mode", "min_corner"));
        Component b = new Component("MASS_MAIN", "main", new Vec3i(4, 0, 4),
                new Dimensions(8, 8, 6), List.of(),
                Map.of("component_id", "primary_b", "anchor_mode", "min_corner",
                        "allow_mass_separation", true));
        Component c = new Component("MASS_MAIN", "main", new Vec3i(20, 0, 20),
                new Dimensions(4, 4, 4), List.of(),
                Map.of("component_id", "far_c", "anchor_mode", "min_corner"));
        LlmPlan repaired = MassFootprintSeparationRepair.apply(
                LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                        .components(List.of(a, b, c)).build());
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        // far_c untouched
        assertEquals(new Vec3i(20, 0, 20), repaired.components().get(2).relativePosition());
        // colliding pair moved or stamped — not stuck overlapping without action
        assertNotEquals(
                a.relativePosition().equals(repaired.components().get(0).relativePosition())
                        && b.relativePosition().equals(repaired.components().get(1).relativePosition()),
                true,
                "authorized pair should separate");
    }

    @Test
    void e01_pairScopedInterlockAllowsOnlyNamedPeer() {
        Component a = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(4, 4, 4), List.of(),
                Map.of("component_id", "vol_a", "anchor_mode", "min_corner",
                        "intentional_interlock_peer_id", "vol_b",
                        "intentional_interlock_shared_cells", true));
        Component b = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(4, 4, 4), List.of(),
                Map.of("component_id", "vol_b", "anchor_mode", "min_corner",
                        "intentional_interlock_peer_id", "vol_a",
                        "intentional_interlock_shared_cells", true));
        Component c = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(4, 4, 4), List.of(),
                Map.of("component_id", "vol_c", "anchor_mode", "min_corner",
                        "intentional_interlock_peer_id", "vol_a",
                        "intentional_interlock_shared_cells", true));
        var index = new PatchOccupancyIndex();
        assertTrue(index.accept(a, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone"))).isEmpty());
        // A↔B mutual + different material: B may overwrite A's cell
        assertTrue(index.accept(b, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:cobblestone"))).isEmpty());
        // C names A but A does not name C → conflict
        var conflict = index.accept(c, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:oak_planks")));
        assertTrue(conflict.isPresent());
        assertEquals("unauthorized_place_overwrite", conflict.get().reason());
    }

    @Test
    void e02_interlockStampDoesNotSetAllowPatchOverwrite() {
        Component a = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 6), List.of(),
                Map.of("component_id", "volume_a", "anchor_mode", "min_corner",
                        "intentional_interlock", true, "mass_role", "interlock_volume"));
        Component b = new Component("MASS_MAIN", "main", new Vec3i(4, 0, 4),
                new Dimensions(8, 8, 6), List.of(),
                Map.of("component_id", "volume_b", "anchor_mode", "min_corner",
                        "intentional_interlock", true, "mass_role", "interlock_volume"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .components(List.of(a, b))
                .build();
        LlmPlan repaired = MassFootprintSeparationRepair.apply(plan);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        boolean stamped = false;
        for (Component c : repaired.components()) {
            assertNotEquals(Boolean.TRUE, c.params().get("allow_patch_overwrite"),
                    "interlock must not stamp component-wide overwrite");
            if (c.params().get("intentional_interlock_peer_id") != null) stamped = true;
        }
        assertTrue(stamped, "pair peer ids should be stamped");
    }

    @Test
    void f01_steppedFalsePreserved() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "zig");
        params.put("stepped", false);
        params.put("masses", List.of(
                Map.of("offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 8, "depth", 8, "height", 4)),
                Map.of("offset", Map.of("x", 0, "y", 4, "z", 0),
                        "dimensions", Map.of("width", 6, "depth", 6, "height", 4))
        ));
        Component c = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 8), List.of(), params);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .distinguishingFeatures(List.of("stepped ziggurat tiers"))
                .components(List.of(c))
                .build();
        LlmPlan repaired = SteppedMassCueRepair.apply(plan);
        assertEquals(Boolean.FALSE, repaired.components().get(0).params().get("stepped"));
        assertNull(repaired.components().get(0).params().get("stepped_cue_source"));
    }

    @Test
    void f02_eccentricOffsetsNotForcedStepped() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "offset_tower");
        params.put("masses", List.of(
                Map.of("offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 8, "depth", 8, "height", 4)),
                Map.of("offset", Map.of("x", 2, "y", 4, "z", 1),
                        "dimensions", Map.of("width", 4, "depth", 4, "height", 4))
        ));
        Component c = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 8), List.of(), params);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .distinguishingFeatures(List.of("stepped mass tiers"))
                .components(List.of(c))
                .build();
        LlmPlan repaired = SteppedMassCueRepair.apply(plan);
        assertNotEquals(Boolean.TRUE, repaired.components().get(0).params().get("stepped"));
    }

    @Test
    void g01_legalEmptyCsgHostCompiles() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "vanished");
        params.put("anchor_mode", "min_corner");
        params.put("solid", true);
        params.put("masses", List.of(
                Map.of("operation", "add",
                        "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 4, "depth", 4, "height", 4)),
                Map.of("operation", "subtract",
                        "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 4, "depth", 4, "height", 4))
        ));
        Component c = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(4, 4, 4), List.of(), params);
        var patches = new MassMainGenerator().generate(new SemanticComponent("MASS_MAIN", null, c));
        assertTrue(patches.isEmpty(), "fully subtracted CSG host emits no voxels");
        ComponentPlanCompiler.compile(LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .planMeta(Map.of(
                        "design_requirements", Map.of(
                                "facade", "OMITTED",
                                "entrance", "OMITTED",
                                "roof", "OMITTED",
                                "circulation", "OMITTED")))
                .components(List.of(c))
                .build());
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> "legal empty must not gap: " + AssemblyCompileDiagnostics.get());
    }
}
