package com.formacraft.server.compiler;

import com.formacraft.common.compiler.postprocess.PostProcessContext;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.CompileCapabilityDiagnostics;
import com.formacraft.common.generation.component.impl.MassMainGenerator;
import com.formacraft.common.generation.component.util.BuildingGeometryContext;
import com.formacraft.common.generation.component.util.MassBooleanComposition;
import com.formacraft.common.generation.component.util.ResolvedFloorLayout;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Layout;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 02.3.2 — Floor Geometry & Storey Levels (F01–F15).
 */
class FloorGeometry0232Test {

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

    private static Set<Vec3i> floorCellsAt(Map<Vec3i, String> blocks, int y) {
        Set<Vec3i> out = new HashSet<>();
        for (var e : blocks.entrySet()) {
            if (e.getKey().y() != y) continue;
            String b = e.getValue() == null ? "" : e.getValue().toLowerCase();
            if (b.contains("plank") || b.contains("floor") || b.contains("slab")
                    || b.contains("carpet") || b.contains("wool")) {
                out.add(e.getKey());
            }
        }
        // Fallback: any non-wall cell on storey y that is oak / stone_bricks used as floor
        if (out.isEmpty()) {
            for (var e : blocks.entrySet()) {
                if (e.getKey().y() != y) continue;
                String b = e.getValue() == null ? "" : e.getValue().toLowerCase();
                if (b.contains("oak") || b.contains("spruce") || b.contains("birch")
                        || b.contains("dark_oak") || b.contains("jungle") || b.contains("acacia")
                        || b.contains("mangrove") || b.contains("cherry") || b.contains("bamboo")
                        || b.contains("crimson") || b.contains("warped")) {
                    out.add(e.getKey());
                }
            }
        }
        return out;
    }

    private static Map<String, Object> hollowFloors(int floorCount, int floorHeight) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("hollow", true);
        extra.put("floor_count", floorCount);
        extra.put("floor_height", floorHeight);
        extra.put("floor_block", "minecraft:oak_planks");
        extra.put("wall_block", "minecraft:stone_bricks");
        return extra;
    }

    @Test
    void f01_singleStoreyGroundSlab() {
        Map<String, Object> extra = hollowFloors(1, 4);
        extra.put("masses", List.of(
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 8, "depth", 8, "height", 4))
        ));
        // size=1 nested → not needsCsg; force via flag
        extra.put("mass_boolean_composition", true);
        // Need size>=2 for needsCsg with flag — add tiny union no-op? Or use plain hollow without CSG.
        Component c = boxMass("f01", 8, 8, 4, hollowFloors(1, 4));
        var blocks = generate(c);
        Set<Vec3i> floors = floorCellsAt(blocks, 0);
        assertFalse(floors.isEmpty(), "ground slab expected");
        assertTrue(floors.contains(new Vec3i(2, 0, 2)) || floors.contains(new Vec3i(3, 0, 3)));
        assertTrue(floors.stream().noneMatch(p -> p.y() == 4));
    }

    @Test
    void f02_multiStoreyFloorYs() {
        Map<String, Object> extra = hollowFloors(3, 4);
        extra.put("masses", List.of(
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 10, "depth", 10, "height", 12)),
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 10, "depth", 10, "height", 12))
        ));
        Component c = boxMass("f02", 10, 10, 12, extra);
        ResolvedFloorLayout layout = ResolvedFloorLayout.from(c, 12);
        assertEquals(List.of(0, 4, 8), layout.floorYs());
        assertTrue(layout.fits());
        var blocks = generate(c);
        assertFalse(floorCellsAt(blocks, 0).isEmpty());
        assertFalse(floorCellsAt(blocks, 4).isEmpty());
        assertFalse(floorCellsAt(blocks, 8).isEmpty());
        assertTrue(floorCellsAt(blocks, 2).isEmpty() || floorCellsAt(blocks, 2).stream()
                .allMatch(p -> blocks.get(p) != null && blocks.get(p).contains("stone")));
    }

    @Test
    void f03_tallGroundClearHeight() {
        Map<String, Object> extra = hollowFloors(2, 4);
        extra.put("ground_clear_height", 6);
        extra.put("masses", List.of(
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 8, "depth", 8, "height", 10)),
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 8, "depth", 8, "height", 10))
        ));
        Component c = boxMass("f03", 8, 8, 10, extra);
        ResolvedFloorLayout layout = ResolvedFloorLayout.from(c, 10);
        assertEquals(List.of(0, 6), layout.floorYs());
        var blocks = generate(c);
        assertFalse(floorCellsAt(blocks, 0).isEmpty());
        assertFalse(floorCellsAt(blocks, 6).isEmpty());
        assertTrue(floorCellsAt(blocks, 4).isEmpty()
                || floorCellsAt(blocks, 4).stream().noneMatch(p -> {
                    String b = blocks.get(p);
                    return b != null && b.contains("oak");
                }));
    }

    @Test
    void f04_slabThicknessClampedToEnvelope() {
        Map<String, Object> extra = hollowFloors(2, 4);
        extra.put("slab_thickness", 3);
        extra.put("masses", List.of(
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 8, "depth", 8, "height", 8)),
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 8, "depth", 8, "height", 8))
        ));
        Component c = boxMass("f04", 8, 8, 8, extra);
        ResolvedFloorLayout layout = ResolvedFloorLayout.from(c, 8);
        assertEquals(3, layout.slabThickness());
        assertEquals(1, layout.clearHeight()); // 4 - 3
        var blocks = generate(c);
        // Upper slab at y=4 with thickness 3 → y=4,5,6 only (not 7 beyond last band care; envelope max 8)
        assertFalse(floorCellsAt(blocks, 4).isEmpty());
        assertFalse(floorCellsAt(blocks, 5).isEmpty());
        assertFalse(floorCellsAt(blocks, 6).isEmpty());
        assertTrue(floorCellsAt(blocks, 7).isEmpty()
                || floorCellsAt(blocks, 7).stream().noneMatch(p -> {
                    String b = blocks.get(p);
                    return b != null && b.contains("oak");
                }));
    }

    @Test
    void f05_floorCountVsHeightConflictGap() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "overflow");
        params.put("floor_count", 5);
        params.put("floor_height", 4);
        params.put("height", 10);
        params.put("anchor_mode", "min_corner");
        com.formacraft.common.typology.UserConstraintContract.stampVerified(params, "height");
        Component mass = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 10), List.of(), params);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(List.of(mass))
                .build();
        LlmPlan out = FloorLayoutOverflowRepair.apply(plan);
        assertTrue(out.hasCapabilityGap());
        assertEquals("E_FLOOR_LAYOUT_OVERFLOW", out.capabilityGap().code());
        assertEquals(10, out.components().getFirst().dimensions().height());
    }

    @Test
    void f06_lShapeFloorsKeepVoid() {
        Map<String, Object> extra = hollowFloors(2, 4);
        extra.put("plan_pattern", "l_shape");
        extra.put("masses", List.of(
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 10, "depth", 4, "height", 8)),
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 4, "depth", 10, "height", 8))
        ));
        Component c = boxMass("f06", 10, 10, 8, extra);
        var blocks = generate(c);
        Set<Vec3i> floors0 = floorCellsAt(blocks, 0);
        assertFalse(floors0.isEmpty());
        // Outside L: corner (8,0,8) should not be in UNION of 10x4 and 4x10
        assertNull(blocks.get(new Vec3i(8, 0, 8)), "L void must stay empty");
        assertFalse(floors0.contains(new Vec3i(8, 0, 8)));
    }

    @Test
    void f07_uShapeFloorsKeepVoid() {
        Map<String, Object> extra = hollowFloors(1, 4);
        // U via three UNION boxes
        extra.put("masses", List.of(
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 3, "depth", 10, "height", 4)),
                Map.of("operation", "add", "offset", Map.of("x", 7, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 3, "depth", 10, "height", 4)),
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 7),
                        "dimensions", Map.of("width", 10, "depth", 3, "height", 4))
        ));
        Component c = boxMass("f07", 10, 10, 4, extra);
        var blocks = generate(c);
        assertNull(blocks.get(new Vec3i(5, 0, 3)), "U courtyard void");
        assertFalse(floorCellsAt(blocks, 0).contains(new Vec3i(5, 0, 3)));
        assertFalse(floorCellsAt(blocks, 0).isEmpty());
    }

    @Test
    void f08_courtyardFloorsKeepHole() {
        Map<String, Object> extra = hollowFloors(2, 4);
        extra.put("plan_pattern", "courtyard");
        extra.put("courtyard_ratio", 0.4);
        Component c = boxMass("f08", 12, 12, 8, extra);
        var blocks = generate(c);
        Set<Vec3i> floors0 = floorCellsAt(blocks, 0);
        assertFalse(floors0.isEmpty());
        // Non-CSG courtyard places COURTYARD_FLOOR at y=0 in the hole; upper slabs must keep the void.
        assertNull(blocks.get(new Vec3i(6, 4, 6)), "upper courtyard void must stay open");
        assertFalse(floorCellsAt(blocks, 4).contains(new Vec3i(6, 4, 6)));
    }

    @Test
    void f09_csgSubtractAtriumNoFloorsInVoid() {
        Map<String, Object> extra = hollowFloors(2, 4);
        extra.put("masses", List.of(
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 12, "depth", 12, "height", 8)),
                Map.of("operation", "subtract", "offset", Map.of("x", 4, "y", 0, "z", 4),
                        "dimensions", Map.of("width", 4, "depth", 4, "height", 8))
        ));
        Component c = boxMass("f09", 12, 12, 8, extra);
        var blocks = generate(c);
        assertNull(blocks.get(new Vec3i(5, 0, 5)));
        assertNull(blocks.get(new Vec3i(5, 4, 5)));
        assertFalse(floorCellsAt(blocks, 0).contains(new Vec3i(5, 0, 5)));
        assertFalse(floorCellsAt(blocks, 4).isEmpty());
    }

    @Test
    void f10_steppedSetbackPerYFootprint() {
        Map<String, Object> extra = hollowFloors(2, 4);
        extra.put("masses", List.of(
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 12, "depth", 12, "height", 4)),
                Map.of("operation", "add", "offset", Map.of("x", 2, "y", 4, "z", 2),
                        "dimensions", Map.of("width", 8, "depth", 8, "height", 4))
        ));
        Component c = boxMass("f10", 12, 12, 8, extra);
        var blocks = generate(c);
        assertFalse(floorCellsAt(blocks, 0).isEmpty());
        assertFalse(floorCellsAt(blocks, 4).isEmpty());
        // Upper footprint smaller — corner of base should not have upper floor
        assertFalse(floorCellsAt(blocks, 4).contains(new Vec3i(0, 4, 0)));
        assertTrue(floorCellsAt(blocks, 4).contains(new Vec3i(4, 4, 4))
                || floorCellsAt(blocks, 4).contains(new Vec3i(5, 4, 5)));
    }

    @Test
    void f11_eccentricNegativeOriginHostLocalYs() {
        Map<String, Object> extra = hollowFloors(2, 4);
        extra.put("masses", List.of(
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 8, "depth", 8, "height", 8)),
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 8, "depth", 8, "height", 8))
        ));
        Map<String, Object> params = new HashMap<>(extra);
        params.put("component_id", "f11");
        params.put("anchor_mode", "min_corner");
        Component c = new Component("MASS_MAIN", "main", new Vec3i(-6, 2, -3),
                new Dimensions(8, 8, 8), List.of(), params);
        ResolvedFloorLayout layout = ResolvedFloorLayout.from(c, 8);
        assertEquals(List.of(0, 4), layout.floorYs()); // host-local
        var blocks = generate(c);
        // Plan-space floors at origin.y + local
        assertFalse(floorCellsAt(blocks, 2).isEmpty());
        assertFalse(floorCellsAt(blocks, 6).isEmpty());
    }

    @Test
    void f12_crossSlotPlanSpaceFloors() {
        Map<String, Object> extra = hollowFloors(2, 4);
        extra.put("masses", List.of(
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 8, "depth", 8, "height", 8)),
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 8, "depth", 8, "height", 8))
        ));
        Map<String, Object> params = new HashMap<>(extra);
        params.put("component_id", "f12");
        params.put("anchor_mode", "min_corner");
        Component c = new Component("MASS_MAIN", "pad", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 8), List.of(), params);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .layout(new Layout(null, false, List.of(
                        new Slot("pad", new Vec3i(20, 5, 10), null, null, null, null))))
                .components(List.of(c))
                .build();
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(CompileCapabilityDiagnostics.hasGap(),
                () -> String.valueOf(CompileCapabilityDiagnostics.get()));
        assertFalse(patches.isEmpty(), "compile-through patches");
        Map<Vec3i, String> blocks = PatchTestSnapshot.blocks(patches);
        // Slot shifts plan-space by (20,5,10). Floors use host-local Y + slot.
        long oakAt5 = blocks.entrySet().stream()
                .filter(e -> e.getKey().y() == 5 && e.getValue() != null && e.getValue().contains("oak"))
                .count();
        long oakAt9 = blocks.entrySet().stream()
                .filter(e -> e.getKey().y() == 9 && e.getValue() != null && e.getValue().contains("oak"))
                .count();
        assertTrue(oakAt5 > 0 || !floorCellsAt(blocks, 5).isEmpty(), "ground floor at slot Y");
        assertTrue(oakAt9 > 0 || !floorCellsAt(blocks, 9).isEmpty(), "second floor at slot Y + 4");
        // SlotPrimaryAnchorRepair may relativize primary XZ to origin; Y shift from slot remains.
        assertTrue(blocks.keySet().stream().anyMatch(p -> p.y() >= 5), "slot Y applied to plan space");
    }

    @Test
    void f13_protectedVoidStairWellInterface() {
        Map<String, Object> extra = hollowFloors(2, 4);
        extra.put("protected_voids", List.of(
                Map.of("x", 3, "y", 0, "z", 3),
                Map.of("x", 3, "y", 4, "z", 3)
        ));
        extra.put("masses", List.of(
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 8, "depth", 8, "height", 8)),
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 8, "depth", 8, "height", 8))
        ));
        Component c = boxMass("f13", 8, 8, 8, extra);
        var blocks = generate(c);
        assertFalse(floorCellsAt(blocks, 0).contains(new Vec3i(3, 0, 3)));
        assertFalse(floorCellsAt(blocks, 4).contains(new Vec3i(3, 4, 3)));
        Set<Long> keys = MassBooleanComposition.protectedVoidKeys(c);
        assertTrue(keys.contains(MassBooleanComposition.pack3(3, 0, 3)));
    }

    @Test
    void f14_solidAndLegalEmpty() {
        Map<String, Object> solid = hollowFloors(2, 4);
        solid.put("solid", true);
        solid.put("masses", List.of(
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 6, "depth", 6, "height", 8)),
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 6, "depth", 6, "height", 8))
        ));
        Component solidMass = boxMass("f14s", 6, 6, 8, solid);
        var solidBlocks = generate(solidMass);
        // Solid fill — no separate hollow floor planks forced at mid levels as oak over stone
        assertNotNull(solidBlocks.get(new Vec3i(2, 2, 2)));

        Map<String, Object> empty = new HashMap<>();
        empty.put("solid", true);
        empty.put("masses", List.of(
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 3, "depth", 3, "height", 3)),
                Map.of("operation", "subtract", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 3, "depth", 3, "height", 3))
        ));
        Component emptyMass = boxMass("f14e", 3, 3, 3, empty);
        var patches = new MassMainGenerator().generate(new SemanticComponent("MASS_MAIN", null, emptyMass));
        assertTrue(patches.isEmpty(), "legal empty CSG must not emit orphan floors");
        assertFalse(CompileCapabilityDiagnostics.hasGap());
    }

    @Test
    void f15_addOnlyUnifiedFloorsContinuous() {
        Map<String, Object> extra = hollowFloors(2, 4);
        extra.put("masses", List.of(
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 8, "depth", 8, "height", 8)),
                Map.of("operation", "add", "offset", Map.of("x", 6, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 8, "depth", 8, "height", 8))
        ));
        Component c = boxMass("f15", 14, 8, 8, extra);
        assertTrue(MassBooleanComposition.needsCsg(c));
        var blocks = generate(c);
        Set<Vec3i> floors0 = floorCellsAt(blocks, 0);
        assertFalse(floors0.isEmpty());
        // Continuous across former dual-emit mid partition (x=6..7 interior)
        assertTrue(floors0.contains(new Vec3i(7, 0, 4)) || floors0.contains(new Vec3i(6, 0, 3))
                || floors0.contains(new Vec3i(8, 0, 4)));
        // No stone wall partition cutting through the union interior at mid height floor
        String mid = blocks.get(new Vec3i(7, 0, 4));
        if (mid != null) {
            assertFalse(mid.contains("stone_brick") && !mid.contains("oak"),
                    "mid cell should be floor continuity not dual-shell wall");
        }
    }

    @Test
    void domainHandoffSurvivesIntoBuildingVolume() {
        Map<String, Object> extra = hollowFloors(1, 4);
        extra.put("masses", List.of(
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 6, "depth", 6, "height", 4)),
                Map.of("operation", "subtract", "offset", Map.of("x", 2, "y", 0, "z", 2),
                        "dimensions", Map.of("width", 2, "depth", 2, "height", 4))
        ));
        Component c = boxMass("handoff", 6, 6, 4, extra);
        List<PostProcessContext.BuildingVolume> volumes = new ArrayList<>();
        try (var scope = BuildingGeometryContext.open()) {
            BuildingGeometryContext.putMass(c);
            // Mimic emit registration path
            var csg = BuildingGeometryContext.domainOrCompose(c);
            assertFalse(csg.isEmpty());
            volumes.add(new PostProcessContext.BuildingVolume(
                    "main",
                    new com.formacraft.common.generation.component.util.ComponentFootprintUtil.Bounds(
                            0, 0, 0, 6, 4, 6),
                    4, "handoff", "handoff", true, csg, new Vec3i(0, 0, 0), Set.of()));
        }
        assertFalse(BuildingGeometryContext.isActive());
        var vol = volumes.getFirst();
        assertTrue(vol.domainContains(0, 0, 0));
        assertFalse(vol.domainContains(2, 0, 2)); // carved
        assertTrue(vol.isProtectedVoid(2, 0, 2));
        assertTrue(vol.boundsContains(2, 0, 2)); // AABB still contains carve
    }

    @Test
    void cpc_compileThroughAddOnlyFloors() {
        Map<String, Object> extra = hollowFloors(2, 4);
        extra.put("masses", List.of(
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 10, "depth", 10, "height", 8)),
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 10, "depth", 10, "height", 8))
        ));
        Component c = boxMass("cpc_floors", 10, 10, 8, extra);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .components(List.of(c))
                .build();
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(CompileCapabilityDiagnostics.hasGap(),
                () -> String.valueOf(CompileCapabilityDiagnostics.get()));
        assertFalse(patches.isEmpty(), "compile-through patches");
        Map<Vec3i, String> blocks = PatchTestSnapshot.blocks(patches);
        long oak0 = blocks.entrySet().stream()
                .filter(e -> e.getKey().y() == 0 && e.getValue() != null && e.getValue().contains("oak"))
                .count();
        long oak4 = blocks.entrySet().stream()
                .filter(e -> e.getKey().y() == 4 && e.getValue() != null && e.getValue().contains("oak"))
                .count();
        assertTrue(oak0 > 0 || !floorCellsAt(blocks, 0).isEmpty());
        assertTrue(oak4 > 0 || !floorCellsAt(blocks, 4).isEmpty());
    }

    @Test
    void separation_authorizedMoverMovesPeerStays() {
        Component a = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 6), List.of(),
                Map.of("component_id", "primary_a", "anchor_mode", "min_corner",
                        "allow_mass_separation", true));
        Component b = new Component("MASS_MAIN", "main", new Vec3i(4, 0, 4),
                new Dimensions(8, 8, 6), List.of(),
                Map.of("component_id", "primary_b", "anchor_mode", "min_corner"));
        LlmPlan repaired = MassFootprintSeparationRepair.apply(
                LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                        .components(List.of(a, b)).build());
        Vec3i aPos = repaired.components().stream()
                .filter(x -> "primary_a".equals(String.valueOf(x.params().get("component_id"))))
                .findFirst().orElseThrow().relativePosition();
        Vec3i bPos = repaired.components().stream()
                .filter(x -> "primary_b".equals(String.valueOf(x.params().get("component_id"))))
                .findFirst().orElseThrow().relativePosition();
        // Authorized earlier-id mover moves; peer stays at authored position.
        assertEquals(new Vec3i(4, 0, 4), bPos);
        assertNotEquals(new Vec3i(0, 0, 0), aPos);
    }
}
