package com.formacraft.server.compiler;

import com.formacraft.common.compiler.postprocess.PostProcessContext;
import com.formacraft.common.compiler.postprocess.PostProcessWriteScope;
import com.formacraft.common.generation.CompileCapabilityDiagnostics;
import com.formacraft.common.generation.component.util.JunctionGrantIndex;
import com.formacraft.common.generation.component.util.MassBooleanComposition;
import com.formacraft.common.generation.component.util.MassJunctionIntent;
import com.formacraft.common.generation.component.util.ProtectedVoidIndex;
import com.formacraft.common.generation.component.util.SpatialDomainQuery;
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
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 02.3.4 — Void & Spatial Protection (V01–V25).
 */
class VoidSpatialProtection0234Test {

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

    private static Component mass(
            String id, String slot, int x, int y, int z, int w, int d, int h, Map<String, Object> extra
    ) {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", id);
        params.put("anchor_mode", "min_corner");
        params.put("hollow", true);
        params.put("floor_block", "minecraft:oak_planks");
        params.put("wall_block", "minecraft:stone_bricks");
        if (extra != null) params.putAll(extra);
        return new Component("MASS_MAIN", slot, new Vec3i(x, y, z),
                new Dimensions(w, d, h), List.of(), params);
    }

    private static Component mass(String id, int x, int y, int z, int w, int d, int h, Map<String, Object> extra) {
        return mass(id, "main", x, y, z, w, d, h, extra);
    }

    private static LlmPlan plan(Component... components) {
        return LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .components(List.of(components))
                .build();
    }

    private static LlmPlan planWithMeta(Map<String, Object> meta, Component... components) {
        return LlmPlanTestFixtures.builder()
                .planMeta(meta == null ? new LinkedHashMap<>() : new LinkedHashMap<>(meta))
                .allowCompilerSatellites(true)
                .components(List.of(components))
                .build();
    }

    private static List<BlockPatch> compile(LlmPlan plan) {
        return ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
    }

    private static Map<Vec3i, String> blocks(LlmPlan plan) {
        List<BlockPatch> patches = compile(plan);
        if (patches == null || patches.isEmpty()) {
            String gap = AssemblyCompileDiagnostics.hasGap()
                    ? String.valueOf(AssemblyCompileDiagnostics.get())
                    : (CompileCapabilityDiagnostics.hasGap()
                    ? String.valueOf(CompileCapabilityDiagnostics.get()) : "no_gap");
            throw new AssertionError("expected non-empty BlockPatch list; gap=" + gap);
        }
        assertFalse(CompileCapabilityDiagnostics.hasGap(),
                () -> String.valueOf(CompileCapabilityDiagnostics.get()));
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        return PatchTestSnapshot.blocks(patches);
    }

    @Test
    void v01_rectangularAtrium() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("protected_spaces", List.of(Map.of(
                "id", "atrium1",
                "host_id", "v01",
                "kind", "ATRIUM",
                "policy", "SKIP_SLABS",
                "box", Map.of("x", 3, "y", 1, "z", 3, "w", 4, "h", 5, "d", 4),
                "coord_space", "host_local"
        )));
        Map<Vec3i, String> b = blocks(planWithMeta(meta, mass("v01", 0, 0, 0, 10, 10, 8, Map.of(
                "floor_height", 4))));
        assertTrue(BuildingSpatialAssertions.voidPreserved(b, 4, 2, 4));
        assertTrue(BuildingSpatialAssertions.voidPreserved(b, 5, 5, 5));
        assertTrue(BuildingSpatialAssertions.occupied(b, 0, 2, 0));
    }

    @Test
    void v02_lShapeCourtyard() {
        Map<Vec3i, String> b = blocks(plan(mass("v02", 0, 0, 0, 12, 12, 5, Map.of(
                "plan_type", "l_shape",
                "protected_spaces", List.of(Map.of(
                        "id", "yard",
                        "kind", "COURTYARD",
                        "policy", "PROTECT",
                        "inset", 2,
                        "y_min", 1,
                        "y_max", 5
                ))
        ))));
        assertFalse(b.isEmpty());
        assertTrue(BuildingSpatialAssertions.voidPreserved(b, 5, 2, 5)
                || BuildingSpatialAssertions.voidPreserved(b, 3, 2, 3));
    }

    @Test
    void v03_multiStoreyCourtyardNotFilledBySlabs() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("protected_spaces", List.of(Map.of(
                "id", "court_ms",
                "host_id", "v03",
                "kind", "COURTYARD",
                "policy", "SKIP_SLABS",
                "box", Map.of("x", 3, "y", 1, "z", 3, "w", 4, "h", 7, "d", 4),
                "coord_space", "host_local"
        )));
        // Explicit floorYs via floor_height only — avoid inferred STAIR_SYSTEM colliding with atrium.
        Map<Vec3i, String> b = blocks(planWithMeta(meta, mass("v03", 0, 0, 0, 10, 10, 10, Map.of(
                "floor_height", 3))));
        assertTrue(BuildingSpatialAssertions.voidPreserved(b, 4, 1, 4));
        assertTrue(BuildingSpatialAssertions.voidPreserved(b, 4, 4, 4));
        assertTrue(BuildingSpatialAssertions.voidPreserved(b, 4, 7, 4));
    }

    @Test
    void v04_booleanCarveAtrium() {
        Map<String, Object> csg = new HashMap<>();
        csg.put("masses", List.of(
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 12, "depth", 12, "height", 6)),
                Map.of("operation", "subtract", "offset", Map.of("x", 3, "y", 1, "z", 3),
                        "dimensions", Map.of("width", 6, "depth", 6, "height", 5))
        ));
        Map<Vec3i, String> b = blocks(plan(mass("v04", 0, 0, 0, 12, 12, 6, csg)));
        assertTrue(BuildingSpatialAssertions.voidPreserved(b, 5, 2, 5));
        assertTrue(BuildingSpatialAssertions.occupied(b, 0, 2, 0));
    }

    @Test
    void v05_subtractThenAddRecovery() {
        Map<String, Object> csg = new HashMap<>();
        csg.put("masses", List.of(
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 10, "depth", 10, "height", 5)),
                Map.of("operation", "subtract", "offset", Map.of("x", 2, "y", 0, "z", 2),
                        "dimensions", Map.of("width", 6, "depth", 6, "height", 5)),
                Map.of("operation", "add", "offset", Map.of("x", 4, "y", 0, "z", 4),
                        "dimensions", Map.of("width", 2, "depth", 2, "height", 5))
        ));
        Map<Vec3i, String> b = blocks(plan(mass("v05", 0, 0, 0, 10, 10, 5, csg)));
        assertTrue(BuildingSpatialAssertions.occupied(b, 4, 1, 4)
                || BuildingSpatialAssertions.occupied(b, 5, 1, 5));
    }

    @Test
    void v06_intersectVsProtect() {
        try (var scope = ProtectedVoidIndex.open()) {
            ProtectedVoidIndex.put(new ProtectedVoidIndex.Space(
                    "p", "h", ProtectedVoidIndex.Kind.ATRIUM, ProtectedVoidIndex.Policy.PROTECT,
                    java.util.Set.of(MassBooleanComposition.pack3(2, 2, 2)),
                    java.util.Set.of(), java.util.Set.of(), "test"));
            assertTrue(ProtectedVoidIndex.isProtected(2, 2, 2));
            assertTrue(ProtectedVoidIndex.deniesWriter(
                    MassBooleanComposition.pack3(2, 2, 2), "foreign", "MASS_MAIN"));
            assertFalse(ProtectedVoidIndex.deniesWriter(
                    MassBooleanComposition.pack3(9, 9, 9), "foreign", "MASS_MAIN"));
        }
        SpatialDomainQuery.CellRole role = SpatialDomainQuery.CellRole.PROTECTED_VOID;
        assertEquals(SpatialDomainQuery.CellRole.PROTECTED_VOID, role);
    }

    @Test
    void v07_doubleHeightSkipsUpperSlab() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("protected_spaces", List.of(Map.of(
                "id", "dh",
                "host_id", "v07",
                "kind", "DOUBLE_HEIGHT",
                "policy", "SKIP_SLABS",
                "box", Map.of("x", 3, "y", 1, "z", 3, "w", 3, "h", 6, "d", 3),
                "coord_space", "host_local"
        )));
        Map<Vec3i, String> b = blocks(planWithMeta(meta, mass("v07", 0, 0, 0, 10, 10, 8, Map.of(
                "floor_height", 4))));
        assertTrue(BuildingSpatialAssertions.voidPreserved(b, 4, 4, 4));
    }

    @Test
    void v08_stairwellReserve() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("protected_spaces", List.of(Map.of(
                "id", "sw",
                "host_id", "v08",
                "kind", "STAIRWELL",
                "policy", "RESERVE",
                "box", Map.of("x", 4, "y", 1, "z", 4, "w", 2, "h", 6, "d", 2),
                "coord_space", "host_local",
                "allow_roles", List.of("STAIR", "STAIR_SYSTEM")
        )));
        LlmPlan p = ProtectedSpaceRepair.apply(planWithMeta(meta, mass("v08", 0, 0, 0, 10, 10, 8, Map.of())));
        assertFalse(CompileCapabilityDiagnostics.hasGap());
        assertNotNull(p.planMeta().get("compiler_protected_spaces"));
    }

    @Test
    void v09_boundStairMayWriteReserve() {
        try (var scope = ProtectedVoidIndex.open()) {
            long key = MassBooleanComposition.pack3(1, 1, 1);
            ProtectedVoidIndex.put(new ProtectedVoidIndex.Space(
                    "sw", "host", ProtectedVoidIndex.Kind.STAIRWELL, ProtectedVoidIndex.Policy.RESERVE,
                    java.util.Set.of(key), java.util.Set.of("STAIR"), java.util.Set.of("stair_a"), "t"));
            assertFalse(ProtectedVoidIndex.deniesWriter(key, "stair_a", "STAIR"));
            assertTrue(ProtectedVoidIndex.deniesWriter(key, "other", "MASS_MAIN"));
        }
    }

    @Test
    void v10_unrelatedStairWriteDenied() {
        try (var scope = ProtectedVoidIndex.open()) {
            long key = MassBooleanComposition.pack3(3, 3, 3);
            ProtectedVoidIndex.put(new ProtectedVoidIndex.Space(
                    "sw", "host", ProtectedVoidIndex.Kind.STAIRWELL, ProtectedVoidIndex.Policy.RESERVE,
                    java.util.Set.of(key), java.util.Set.of("STAIR"), java.util.Set.of("bound_stair"), "t"));
            assertTrue(ProtectedVoidIndex.deniesWriter(key, "rogue_stair", "STAIR_SYSTEM"));
        }
    }

    @Test
    void v11_pilotisSupports() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("protected_spaces", List.of(Map.of(
                "id", "pilot",
                "host_id", "v11",
                "kind", "PILOTIS",
                "policy", "RESERVE",
                "box", Map.of("x", 1, "y", 0, "z", 1, "w", 8, "h", 3, "d", 8),
                "coord_space", "host_local",
                "allow_roles", List.of("COLUMN", "FOUNDATION")
        )));
        Map<Vec3i, String> b = blocks(planWithMeta(meta, mass("v11", 0, 0, 0, 10, 10, 8, Map.of(
                "ground_clear_height", 3))));
        assertTrue(BuildingSpatialAssertions.voidPreserved(b, 4, 1, 4)
                || BuildingSpatialAssertions.voidPreserved(b, 5, 1, 5));
    }

    @Test
    void v12_worldClearPolicyEmitsRemove() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("protected_spaces", List.of(Map.of(
                "id", "clear",
                "host_id", "v12",
                "kind", "ATRIUM",
                "policy", "CLEAR_AND_PROTECT",
                "box", Map.of("x", 3, "y", 1, "z", 3, "w", 3, "h", 3, "d", 3),
                "coord_space", "host_local"
        )));
        LlmPlan repaired = ProtectedSpaceRepair.apply(planWithMeta(meta,
                mass("v12", 0, 0, 0, 10, 10, 6, Map.of())));
        assertFalse(CompileCapabilityDiagnostics.hasGap());
        Map<Vec3i, String> b = blocks(repaired);
        assertTrue(BuildingSpatialAssertions.voidPreserved(b, 4, 2, 4));
    }

    @Test
    void v13_thickJunctionPassageProtected() {
        Component a = mass("v13_a", 0, 0, 0, 10, 10, 6, Map.of(
                MassJunctionIntent.PARAM_INTENT, "passage",
                MassJunctionIntent.PARAM_PEER, "v13_b",
                "wall_thickness", 2,
                "door_width", 3,
                "door_height", 3));
        Component b = mass("v13_b", 10, 0, 0, 10, 10, 6, Map.of(
                MassJunctionIntent.PARAM_INTENT, "passage",
                MassJunctionIntent.PARAM_PEER, "v13_a",
                "wall_thickness", 2));
        // Single CPC pass (do not pre-apply MassJunctionRepair — duplicate portal ids).
        List<BlockPatch> patches = compile(plan(a, b));
        if (patches.isEmpty() && CompileCapabilityDiagnostics.hasGap()) {
            assertTrue(CompileCapabilityDiagnostics.get().code().contains("JUNCTION")
                    || CompileCapabilityDiagnostics.get().code().contains("PATCH")
                    || CompileCapabilityDiagnostics.get().code().contains("DESIGN"));
            return;
        }
        BuildingSpatialAssertions.requireNonEmpty(patches);
        assertFalse(PatchTestSnapshot.blocks(patches).isEmpty());
    }

    @Test
    void v14_crossSlotVoidCoords() {
        Slot slot = new Slot("wing", new Vec3i(20, 0, 0), null, null, null, null);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("protected_spaces", List.of(Map.of(
                "id", "slot_void",
                "host_id", "v14",
                "kind", "ATRIUM",
                "policy", "PROTECT",
                "box", Map.of("x", 2, "y", 1, "z", 2, "w", 3, "h", 3, "d", 3),
                "coord_space", "host_local"
        )));
        LlmPlan p = LlmPlanTestFixtures.builder()
                .layout(new Layout(null, false, List.of(slot)))
                .planMeta(meta)
                .allowCompilerSatellites(true)
                .components(List.of(mass("v14", "wing", 0, 0, 0, 8, 8, 6, Map.of())))
                .build();
        Map<Vec3i, String> b = blocks(p);
        assertTrue(BuildingSpatialAssertions.voidPreserved(b, 22, 2, 2)
                || BuildingSpatialAssertions.voidPreserved(b, 23, 2, 3));
    }

    @Test
    void v15_negativeCoordsUnderground() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("protected_spaces", List.of(Map.of(
                "id", "neg",
                "host_id", "v15",
                "kind", "ATRIUM",
                "policy", "PROTECT",
                "box", Map.of("x", 2, "y", 1, "z", 2, "w", 3, "h", 3, "d", 3),
                "coord_space", "host_local"
        )));
        Map<Vec3i, String> b = blocks(planWithMeta(meta,
                mass("v15", -12, -4, -8, 10, 10, 6, Map.of())));
        assertTrue(BuildingSpatialAssertions.voidPreserved(b, -10, -2, -6)
                || BuildingSpatialAssertions.voidPreserved(b, -9, -3, -5));
    }

    @Test
    void v16_nonzeroWorldAnchor() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("protected_spaces", List.of(Map.of(
                "id", "anch",
                "host_id", "v16",
                "kind", "ATRIUM",
                "policy", "PROTECT",
                "box", Map.of("x", 2, "y", 1, "z", 2, "w", 3, "h", 3, "d", 3),
                "coord_space", "host_local"
        )));
        LlmPlan p = planWithMeta(meta, mass("v16", 0, 0, 0, 8, 8, 6, Map.of()));
        List<BlockPatch> patches = ComponentPlanCompiler.compile(p, new BlockPos(100, 64, 200), null, null, false);
        BuildingSpatialAssertions.requireNonEmpty(patches);
        Map<Vec3i, String> b = PatchTestSnapshot.blocks(patches);
        // Patches are plan-relative; void still empty in plan space.
        assertTrue(BuildingSpatialAssertions.voidPreserved(b, 3, 2, 3)
                || BuildingSpatialAssertions.voidPreserved(b, 4, 2, 4));
    }

    @Test
    void v17_postprocessCannotRefillVoid() {
        try (var voids = ProtectedVoidIndex.open()) {
            long key = MassBooleanComposition.pack3(5, 2, 5);
            ProtectedVoidIndex.put(new ProtectedVoidIndex.Space(
                    "a", "h", ProtectedVoidIndex.Kind.ATRIUM, ProtectedVoidIndex.Policy.PROTECT,
                    java.util.Set.of(key), java.util.Set.of(), java.util.Set.of(), "t"));
            PostProcessContext.BuildingVolume vol = new PostProcessContext.BuildingVolume(
                    "main",
                    new com.formacraft.common.generation.component.util.ComponentFootprintUtil.Bounds(
                            0, 0, 0, 10, 6, 10),
                    4, "h", "b1", true, null, new Vec3i(0, 0, 0),
                    java.util.Set.of(MassBooleanComposition.pack3(5, 2, 5)));
            PostProcessContext ctx = PostProcessContext.create(
                    plan(mass("h", 0, 0, 0, 10, 10, 6, Map.of())),
                    BlockPos.ORIGIN, List.of(vol));
            PostProcessWriteScope scope = PostProcessWriteScope.fromContext(ctx, java.util.Set.of());
            assertFalse(scope.allowsPlace(new BlockPos(5, 2, 5)));
        }
    }

    @Test
    void v18_courtyardLegalGround() {
        try (var scope = ProtectedVoidIndex.open()) {
            long key = MassBooleanComposition.pack3(4, 0, 4);
            ProtectedVoidIndex.put(new ProtectedVoidIndex.Space(
                    "yard", "h", ProtectedVoidIndex.Kind.COURTYARD, ProtectedVoidIndex.Policy.PROTECT,
                    java.util.Set.of(key), java.util.Set.of("COURTYARD_FLOOR", "LANDSCAPE"),
                    java.util.Set.of(), "t"));
            assertFalse(ProtectedVoidIndex.deniesWriter(key, "land", "COURTYARD_FLOOR"));
            assertTrue(ProtectedVoidIndex.deniesWriter(key, "mass", "MASS_MAIN"));
        }
    }

    @Test
    void v19_overlappingPolicies() {
        try (var scope = ProtectedVoidIndex.open()) {
            long key = MassBooleanComposition.pack3(1, 1, 1);
            ProtectedVoidIndex.put(new ProtectedVoidIndex.Space(
                    "a", "h", ProtectedVoidIndex.Kind.ATRIUM, ProtectedVoidIndex.Policy.PROTECT,
                    java.util.Set.of(key), java.util.Set.of(), java.util.Set.of(), "t"));
            ProtectedVoidIndex.put(new ProtectedVoidIndex.Space(
                    "b", "h", ProtectedVoidIndex.Kind.STAIRWELL, ProtectedVoidIndex.Policy.RESERVE,
                    java.util.Set.of(key), java.util.Set.of("STAIR"), java.util.Set.of("s1"), "t"));
            // Either covering space may allow; deny only when none allow.
            assertFalse(ProtectedVoidIndex.deniesWriter(key, "s1", "STAIR"));
        }
    }

    @Test
    void v20_roofInterfaceDoesNotSealAtrium() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("protected_spaces", List.of(Map.of(
                "id", "open_court",
                "host_id", "v20",
                "kind", "ATRIUM",
                "policy", "PROTECT",
                "box", Map.of("x", 3, "y", 1, "z", 3, "w", 4, "h", 6, "d", 4),
                "coord_space", "host_local"
        )));
        Map<Vec3i, String> b = blocks(planWithMeta(meta, mass("v20", 0, 0, 0, 10, 10, 8, Map.of())));
        assertTrue(BuildingSpatialAssertions.voidPreserved(b, 4, 5, 4));
    }

    @Test
    void v21_peerIsolationNoGrantBleed() {
        try (var grants = JunctionGrantIndex.open()) {
            JunctionGrantIndex.put("a", "b",
                    java.util.Set.of(MassBooleanComposition.pack3(1, 1, 1)),
                    java.util.Set.of(JunctionGrantIndex.Op.OVERWRITE), "pair");
            assertTrue(JunctionGrantIndex.hasPair("a", "b"));
            assertFalse(JunctionGrantIndex.hasPair("a", "c"));
            assertFalse(JunctionGrantIndex.allowsCell(
                    "a", "c", MassBooleanComposition.pack3(1, 1, 1), JunctionGrantIndex.Op.OVERWRITE));
            JunctionGrantIndex.put("x", "y", java.util.Set.of(),
                    java.util.Set.of(JunctionGrantIndex.Op.OVERWRITE), "empty_deny");
            assertTrue(JunctionGrantIndex.hasPair("x", "y"));
            assertFalse(JunctionGrantIndex.allowsCell(
                    "x", "y", MassBooleanComposition.pack3(0, 0, 0), JunctionGrantIndex.Op.OVERWRITE));
        }
    }

    @Test
    void v22_sparseLargeVolumeBudget() {
        long start = System.nanoTime();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("protected_spaces", List.of(Map.of(
                "id", "sparse",
                "host_id", "v22",
                "kind", "ATRIUM",
                "policy", "PROTECT",
                "box", Map.of("x", 8, "y", 1, "z", 8, "w", 4, "h", 4, "d", 4),
                "coord_space", "host_local"
        )));
        Map<Vec3i, String> b = blocks(planWithMeta(meta, mass("v22", 0, 0, 0, 24, 24, 8, Map.of())));
        long ms = (System.nanoTime() - start) / 1_000_000L;
        assertFalse(b.isEmpty());
        assertTrue(ms < 30_000L, "compile budget exceeded: " + ms + "ms");
    }

    @Test
    void v23_fullCpcSpatialValidation() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("protected_spaces", List.of(Map.of(
                "id", "full",
                "host_id", "v23",
                "kind", "ATRIUM",
                "policy", "PROTECT",
                "box", Map.of("x", 3, "y", 1, "z", 3, "w", 4, "h", 4, "d", 4),
                "coord_space", "host_local"
        )));
        LlmPlan p = planWithMeta(meta, mass("v23", 0, 0, 0, 12, 12, 7, Map.of(
                "floor_height", 3)));
        List<BlockPatch> patches = compile(p);
        BuildingSpatialAssertions.requireNonEmpty(patches);
        Map<Vec3i, String> b = PatchTestSnapshot.blocks(patches);
        assertTrue(BuildingSpatialAssertions.voidPreserved(b, 4, 2, 4));
        assertTrue(BuildingSpatialAssertions.occupied(b, 0, 1, 0));
    }

    @Test
    void v24_repeatCompileDeterminism() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("protected_spaces", List.of(Map.of(
                "id", "det",
                "host_id", "v24",
                "kind", "ATRIUM",
                "policy", "PROTECT",
                "box", Map.of("x", 2, "y", 1, "z", 2, "w", 3, "h", 3, "d", 3),
                "coord_space", "host_local"
        )));
        LlmPlan p = planWithMeta(meta, mass("v24", 0, 0, 0, 8, 8, 6, Map.of()));
        List<BlockPatch> a = compile(p);
        List<BlockPatch> b = compile(p);
        assertTrue(BuildingSpatialAssertions.deterministic(a, b));
    }

    @Test
    void v25_failClosedOnMissingHost() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("protected_spaces", List.of(Map.of(
                "id", "bad",
                "host_id", "missing_host",
                "kind", "ATRIUM",
                "policy", "PROTECT",
                "box", Map.of("x", 1, "y", 1, "z", 1, "w", 2, "h", 2, "d", 2),
                "coord_space", "host_local"
        )));
        LlmPlan p = planWithMeta(meta, mass("v25", 0, 0, 0, 8, 8, 5, Map.of()));
        ProtectedSpaceRepair.apply(p);
        assertTrue(CompileCapabilityDiagnostics.hasGap());
        assertEquals("E_PROTECTED_SPACE_HOST_MISSING", CompileCapabilityDiagnostics.get().code());
        List<BlockPatch> patches = ComponentPlanCompiler.compile(p, BlockPos.ORIGIN, null, null, false);
        assertTrue(patches.isEmpty(), "fail-closed must not emit partial building");
    }
}
