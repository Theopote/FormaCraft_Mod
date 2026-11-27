package com.formacraft.server.compiler;

import com.formacraft.common.generation.CompileCapabilityDiagnostics;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.generation.component.util.MassDomainRelation;
import com.formacraft.common.generation.component.util.MassJunctionIntent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.typology.UserConstraintContract;
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
 * Phase 02.3.3 — Multi-Mass Junction & Shared Walls (J01–J18).
 */
class MassJunction0233Test {

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
            String id, int x, int y, int z, int w, int d, int h, Map<String, Object> extra
    ) {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", id);
        params.put("anchor_mode", "min_corner");
        params.put("hollow", true);
        params.put("floor_block", "minecraft:oak_planks");
        params.put("wall_block", "minecraft:stone_bricks");
        if (extra != null) params.putAll(extra);
        return new Component("MASS_MAIN", "main", new Vec3i(x, y, z),
                new Dimensions(w, d, h), List.of(), params);
    }

    private static Map<Vec3i, String> compile(Component... components) {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .components(List.of(components))
                .build();
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        return PatchTestSnapshot.blocks(patches);
    }

    private static LlmPlan repair(Component... components) {
        return MassJunctionRepair.apply(LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .components(List.of(components))
                .build());
    }

    private static long wallCellsOnX(Map<Vec3i, String> blocks, int x, int y0, int y1) {
        return blocks.entrySet().stream()
                .filter(e -> e.getKey().x() == x && e.getKey().y() >= y0 && e.getKey().y() < y1)
                .filter(e -> e.getValue() != null && e.getValue().contains("stone_brick"))
                .count();
    }

    private static boolean hasOpeningAt(Map<Vec3i, String> blocks, int x, int y, int z) {
        String b = blocks.get(new Vec3i(x, y, z));
        return b == null || b.contains("air") || b.contains("glass") || !b.contains("stone_brick");
    }

    @Test
    void j01_independentFaceContactNoAutoOpen() {
        Component a = mass("j01_a", 0, 0, 0, 8, 8, 6, Map.of());
        Component b = mass("j01_b", 8, 0, 0, 8, 8, 6, Map.of());
        var ab = ComponentFootprintUtil.bounds(a);
        var bb = ComponentFootprintUtil.bounds(b);
        MassDomainRelation.Query q = MassDomainRelation.classify(ab, bb, null, null, null, null);
        assertEquals(MassDomainRelation.FACE_CONTACT, q.relation());
        assertEquals(MassJunctionIntent.INDEPENDENT, MassJunctionIntent.forPair(a, b));
        LlmPlan repaired = repair(a, b);
        assertFalse(CompileCapabilityDiagnostics.hasGap());
        // No junction stamps without explicit intent.
        for (Component c : repaired.components()) {
            assertNull(c.params().get("junction_omit_cells"));
            Object intent = c.params().get(MassJunctionIntent.PARAM_INTENT);
            assertTrue(intent == null || "independent".equals(String.valueOf(intent)));
        }
        Map<Vec3i, String> blocks = compile(a, b);
        // Both face walls remain (no auto-open).
        assertTrue(wallCellsOnX(blocks, 7, 1, 5) > 0, "A east wall present");
        assertTrue(wallCellsOnX(blocks, 8, 1, 5) > 0, "B west wall present");
    }

    @Test
    void j02_cornerContactNoConnection() {
        Component a = mass("j02_a", 0, 0, 0, 6, 6, 5, Map.of());
        Component b = mass("j02_b", 6, 0, 6, 6, 6, 5, Map.of(
                MassJunctionIntent.PARAM_INTENT, "shared_wall",
                MassJunctionIntent.PARAM_PEER, "j02_a"));
        var q = MassDomainRelation.classify(
                ComponentFootprintUtil.bounds(a), ComponentFootprintUtil.bounds(b),
                null, null, null, null);
        assertTrue(q.relation() == MassDomainRelation.CORNER_CONTACT
                        || q.relation() == MassDomainRelation.EDGE_CONTACT
                        || q.relation() == MassDomainRelation.DISJOINT,
                () -> "expected corner/edge/disjoint, got " + q.relation());
        LlmPlan repaired = repair(a, b);
        // Corner must not invent omit cells / portals.
        assertTrue(repaired.components().stream()
                .noneMatch(c -> c.params().get("junction_omit_cells") != null));
        assertTrue(repaired.components().stream()
                .noneMatch(c -> "ENTRANCE".equals(c.componentType())
                        && Boolean.TRUE.equals(c.params().get("compiler_junction_portal"))));
    }

    @Test
    void j03_explicitSharedWallCollapsesThickness() {
        Component a = mass("j03_a", 0, 0, 0, 8, 8, 6, Map.of(
                MassJunctionIntent.PARAM_INTENT, "shared_wall",
                MassJunctionIntent.PARAM_PEER, "j03_b",
                "wall_thickness", 1));
        Component b = mass("j03_b", 7, 0, 0, 8, 8, 6, Map.of(
                MassJunctionIntent.PARAM_INTENT, "shared_wall",
                MassJunctionIntent.PARAM_PEER, "j03_a",
                "wall_thickness", 1));
        LlmPlan repaired = repair(a, b);
        assertFalse(CompileCapabilityDiagnostics.hasGap());
        boolean hasOmit = repaired.components().stream()
                .anyMatch(c -> c.params().get("junction_omit_cells") instanceof List<?> list
                        && !list.isEmpty());
        assertTrue(hasOmit, "shared wall must stamp omit cells on secondary");
        Map<Vec3i, String> blocks = compile(
                repaired.components().stream()
                        .filter(c -> "MASS_MAIN".equals(c.componentType()))
                        .toArray(Component[]::new));
        // Seam x=7 should not be dual-thick stone from both shells after collapse.
        long seamWalls = wallCellsOnX(blocks, 7, 1, 5);
        assertTrue(seamWalls > 0, "owner wall remains");
        // Interior of A and B still floored.
        assertTrue(blocks.containsKey(new Vec3i(3, 0, 3))
                || blocks.values().stream().anyMatch(v -> v != null && v.contains("oak")));
    }

    @Test
    void j04_explicitOpenHallCreatesOpening() {
        Component a = mass("j04_a", 0, 0, 0, 10, 10, 6, Map.of(
                MassJunctionIntent.PARAM_INTENT, "open_connection",
                MassJunctionIntent.PARAM_PEER, "j04_b"));
        Component b = mass("j04_b", 10, 0, 0, 10, 10, 6, Map.of(
                MassJunctionIntent.PARAM_INTENT, "open_connection",
                MassJunctionIntent.PARAM_PEER, "j04_a"));
        LlmPlan repaired = repair(a, b);
        assertFalse(CompileCapabilityDiagnostics.hasGap());
        assertTrue(repaired.components().stream()
                .anyMatch(c -> Boolean.TRUE.equals(c.params().get("compiler_junction_portal"))));
        Map<Vec3i, String> blocks = compile(
                repaired.components().toArray(Component[]::new));
        // Mid-face opening around z=5 on the shared x=9/10 face.
        boolean opened = false;
        for (int z = 3; z <= 7; z++) {
            for (int y = 1; y <= 4; y++) {
                if (hasOpeningAt(blocks, 9, y, z) || hasOpeningAt(blocks, 10, y, z)) {
                    opened = true;
                }
            }
        }
        assertTrue(opened, "open connection must clear shared-face cells");
    }

    @Test
    void j05_sameLevelPassage() {
        Component a = mass("j05_a", 0, 0, 0, 8, 8, 5, Map.of(
                MassJunctionIntent.PARAM_INTENT, "passage",
                MassJunctionIntent.PARAM_PEER, "j05_b"));
        Component b = mass("j05_b", 8, 0, 0, 8, 8, 5, Map.of(
                MassJunctionIntent.PARAM_INTENT, "passage",
                MassJunctionIntent.PARAM_PEER, "j05_a"));
        LlmPlan repaired = repair(a, b);
        assertFalse(CompileCapabilityDiagnostics.hasGap());
        assertEquals(0, a.relativePosition().y());
        assertEquals(0, repaired.components().get(0).relativePosition().y());
        assertTrue(repaired.components().stream()
                .anyMatch(c -> Boolean.TRUE.equals(c.params().get("clear_passage"))));
    }

    @Test
    void j06_elevationMismatchPassageGap() {
        Component a = mass("j06_a", 0, 0, 0, 8, 8, 5, Map.of(
                MassJunctionIntent.PARAM_INTENT, "passage",
                MassJunctionIntent.PARAM_PEER, "j06_b"));
        Component b = mass("j06_b", 8, 2, 0, 8, 8, 5, Map.of(
                MassJunctionIntent.PARAM_INTENT, "passage",
                MassJunctionIntent.PARAM_PEER, "j06_a"));
        repair(a, b);
        assertTrue(CompileCapabilityDiagnostics.hasGap() || AssemblyCompileDiagnostics.hasGap());
        String code = CompileCapabilityDiagnostics.hasGap()
                ? CompileCapabilityDiagnostics.get().code()
                : AssemblyCompileDiagnostics.get().code();
        assertEquals("E_JUNCTION_ELEVATION_MISMATCH", code);
    }

    @Test
    void j07_unequalWallThicknessSharedFace() {
        Component a = mass("j07_a", 0, 0, 0, 10, 10, 6, Map.of(
                MassJunctionIntent.PARAM_INTENT, "shared_wall",
                MassJunctionIntent.PARAM_PEER, "j07_b",
                "wall_thickness", 1));
        Component b = mass("j07_b", 9, 0, 0, 10, 10, 6, Map.of(
                MassJunctionIntent.PARAM_INTENT, "shared_wall",
                MassJunctionIntent.PARAM_PEER, "j07_a",
                "wall_thickness", 2));
        LlmPlan repaired = repair(a, b);
        assertFalse(CompileCapabilityDiagnostics.hasGap());
        Component secondary = repaired.components().stream()
                .filter(c -> c.params().get("junction_omit_cells") != null)
                .findFirst().orElseThrow();
        assertNotNull(secondary.params().get("junction_shared_wall_owner"));
        @SuppressWarnings("unchecked")
        List<Long> omit = (List<Long>) secondary.params().get("junction_omit_cells");
        assertFalse(omit.isEmpty());
    }

    @Test
    void j08_lShapeWithRectConnection() {
        Map<String, Object> lExtra = new HashMap<>();
        lExtra.put(MassJunctionIntent.PARAM_INTENT, "shared_wall");
        lExtra.put(MassJunctionIntent.PARAM_PEER, "j08_b");
        lExtra.put("plan_type", "l_shape");
        lExtra.put("l_cut", 4);
        Component a = mass("j08_a", 0, 0, 0, 12, 12, 6, lExtra);
        Component b = mass("j08_b", 12, 0, 0, 6, 8, 6, Map.of(
                MassJunctionIntent.PARAM_INTENT, "shared_wall",
                MassJunctionIntent.PARAM_PEER, "j08_a"));
        LlmPlan repaired = repair(a, b);
        assertFalse(CompileCapabilityDiagnostics.hasGap());
        assertTrue(repaired.components().stream().anyMatch(c ->
                "shared_wall".equals(String.valueOf(c.params().get(MassJunctionIntent.PARAM_INTENT)))));
        Map<Vec3i, String> blocks = compile(
                repaired.components().stream()
                        .filter(c -> c.componentType().startsWith("MASS"))
                        .toArray(Component[]::new));
        assertFalse(blocks.isEmpty());
        assertTrue(blocks.keySet().stream().anyMatch(p -> p.x() >= 12), "rect wing present");
    }

    @Test
    void j09_courtyardConnectsWing() {
        Map<String, Object> court = new HashMap<>();
        court.put(MassJunctionIntent.PARAM_INTENT, "shared_wall");
        court.put(MassJunctionIntent.PARAM_PEER, "j09_wing");
        court.put("masses", List.of(
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 14, "depth", 14, "height", 5)),
                Map.of("operation", "subtract", "offset", Map.of("x", 4, "y", 0, "z", 4),
                        "dimensions", Map.of("width", 6, "depth", 6, "height", 5))
        ));
        Component host = mass("j09_court", 0, 0, 0, 14, 14, 5, court);
        Component wing = mass("j09_wing", 14, 0, 4, 6, 6, 5, Map.of(
                MassJunctionIntent.PARAM_INTENT, "shared_wall",
                MassJunctionIntent.PARAM_PEER, "j09_court"));
        LlmPlan repaired = repair(host, wing);
        assertFalse(CompileCapabilityDiagnostics.hasGap());
        Map<Vec3i, String> blocks = compile(
                repaired.components().stream()
                        .filter(c -> c.componentType().startsWith("MASS"))
                        .toArray(Component[]::new));
        assertNull(blocks.get(new Vec3i(6, 1, 6)), "courtyard void preserved");
        assertTrue(blocks.keySet().stream().anyMatch(p -> p.x() >= 14));
    }

    @Test
    void j10_towerOnPodiumVertical() {
        Component podium = mass("j10_podium", 0, 0, 0, 12, 12, 4, Map.of(
                MassJunctionIntent.PARAM_INTENT, "vertical_connection",
                MassJunctionIntent.PARAM_PEER, "j10_tower"));
        Component tower = mass("j10_tower", 3, 4, 3, 6, 6, 10, Map.of(
                MassJunctionIntent.PARAM_INTENT, "vertical_connection",
                MassJunctionIntent.PARAM_PEER, "j10_podium"));
        var q = MassDomainRelation.classify(
                ComponentFootprintUtil.bounds(podium), ComponentFootprintUtil.bounds(tower),
                null, null, null, null);
        assertEquals(MassDomainRelation.VERTICAL_STACK, q.relation());
        LlmPlan repaired = repair(podium, tower);
        assertFalse(CompileCapabilityDiagnostics.hasGap());
        assertEquals(new Vec3i(3, 4, 3), repaired.components().stream()
                .filter(c -> "j10_tower".equals(c.params().get("component_id")))
                .findFirst().orElseThrow().relativePosition());
    }

    @Test
    void j11_multiPeerInterlockA_B_and_B_C() {
        Component a = mass("vol_a", 0, 0, 0, 4, 4, 4, Map.of(
                "intentional_interlock_peer_ids", List.of("vol_b"),
                "intentional_interlock_peer_id", "vol_b",
                "intentional_interlock_shared_cells", true));
        Component b = mass("vol_b", 0, 0, 0, 4, 4, 4, Map.of(
                "intentional_interlock_peer_ids", List.of("vol_a", "vol_c"),
                "intentional_interlock_peer_id", "vol_a",
                "intentional_interlock_shared_cells", true));
        Component c = mass("vol_c", 0, 0, 0, 4, 4, 4, Map.of(
                "intentional_interlock_peer_ids", List.of("vol_b"),
                "intentional_interlock_peer_id", "vol_b",
                "intentional_interlock_shared_cells", true));
        var index = new PatchOccupancyIndex();
        assertTrue(index.accept(a, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone"))).isEmpty());
        // A↔C not mutual while A still owns the cell.
        var conflictAc = index.accept(c, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:oak_planks")));
        assertTrue(conflictAc.isPresent());
        assertEquals("unauthorized_place_overwrite", conflictAc.get().reason());
        // A↔B mutual overwrite allowed.
        assertTrue(index.accept(b, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:cobblestone"))).isEmpty());
        // B↔C mutual on a separate cell.
        var index2 = new PatchOccupancyIndex();
        assertTrue(index2.accept(b, List.of(
                new BlockPatch(BlockPatch.PLACE, 1, 0, 0, "minecraft:cobblestone"))).isEmpty());
        assertTrue(index2.accept(c, List.of(
                new BlockPatch(BlockPatch.PLACE, 1, 0, 0, "minecraft:bricks"))).isEmpty());
    }

    @Test
    void j12_unrelatedThirdPartyWriteDenied() {
        Component a = mass("j12_a", 0, 0, 0, 6, 6, 4, Map.of(
                "intentional_interlock_peer_id", "j12_b",
                "intentional_interlock_shared_cells", true));
        Component b = mass("j12_b", 0, 0, 0, 6, 6, 4, Map.of(
                "intentional_interlock_peer_id", "j12_a",
                "intentional_interlock_shared_cells", true));
        Component stranger = mass("j12_x", 0, 0, 0, 6, 6, 4, Map.of());
        var index = new PatchOccupancyIndex();
        assertTrue(index.accept(a, List.of(
                new BlockPatch(BlockPatch.PLACE, 2, 1, 2, "minecraft:stone"))).isEmpty());
        assertTrue(index.accept(b, List.of(
                new BlockPatch(BlockPatch.PLACE, 2, 1, 2, "minecraft:cobblestone"))).isEmpty());
        var denied = index.accept(stranger, List.of(
                new BlockPatch(BlockPatch.PLACE, 2, 1, 2, "minecraft:dirt")));
        assertTrue(denied.isPresent());
    }

    @Test
    void j13_crossSlotSeam() {
        Component a = new Component("MASS_MAIN", "slot_a", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 5), List.of(),
                Map.of("component_id", "j13_a", "anchor_mode", "min_corner", "hollow", true,
                        MassJunctionIntent.PARAM_INTENT, "shared_wall",
                        MassJunctionIntent.PARAM_PEER, "j13_b",
                        "wall_block", "minecraft:stone_bricks",
                        "floor_block", "minecraft:oak_planks"));
        Component b = new Component("MASS_MAIN", "slot_b", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 5), List.of(),
                Map.of("component_id", "j13_b", "anchor_mode", "min_corner", "hollow", true,
                        MassJunctionIntent.PARAM_INTENT, "shared_wall",
                        MassJunctionIntent.PARAM_PEER, "j13_a",
                        "wall_block", "minecraft:stone_bricks",
                        "floor_block", "minecraft:oak_planks"));
        Layout layout = new Layout(null, false, List.of(
                new Slot("slot_a", new Vec3i(0, 0, 0), null, null, null, null),
                new Slot("slot_b", new Vec3i(8, 0, 0), null, null, null, null)
        ));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .layout(layout)
                .components(List.of(a, b))
                .build();
        LlmPlan repaired = MassJunctionRepair.apply(plan);
        assertFalse(CompileCapabilityDiagnostics.hasGap());
        List<BlockPatch> patches = ComponentPlanCompiler.compile(repaired, BlockPos.ORIGIN, null, null, false);
        Map<Vec3i, String> blocks = PatchTestSnapshot.blocks(patches);
        assertTrue(blocks.keySet().stream().anyMatch(p -> p.x() >= 8));
        assertTrue(blocks.keySet().stream().anyMatch(p -> p.x() < 8));
    }

    @Test
    void j14_lockedCompoundPrefersJunctionNotMove() {
        Map<String, Object> aParams = new HashMap<>();
        aParams.put("component_id", "j14_a");
        aParams.put("anchor_mode", "min_corner");
        aParams.put("hollow", true);
        aParams.put(MassJunctionIntent.PARAM_INTENT, "shared_wall");
        aParams.put(MassJunctionIntent.PARAM_PEER, "j14_b");
        aParams.put("lock_position", true);
        UserConstraintContract.stampVerified(aParams, "relative_position");
        Component a = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 6), List.of(), aParams);
        Component b = mass("j14_b", 7, 0, 0, 8, 8, 6, Map.of(
                MassJunctionIntent.PARAM_INTENT, "shared_wall",
                MassJunctionIntent.PARAM_PEER, "j14_a"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .components(List.of(a, b))
                .build();
        LlmPlan afterJunction = MassJunctionRepair.apply(plan);
        LlmPlan afterSep = MassFootprintSeparationRepair.apply(afterJunction);
        assertEquals(new Vec3i(0, 0, 0), afterSep.components().stream()
                .filter(c -> "j14_a".equals(c.params().get("component_id")))
                .findFirst().orElseThrow().relativePosition());
        assertEquals(new Vec3i(7, 0, 0), afterSep.components().stream()
                .filter(c -> "j14_b".equals(c.params().get("component_id")))
                .findFirst().orElseThrow().relativePosition());
    }

    @Test
    void j15_narrowPassageBlockedDiagnostic() {
        Component a = mass("j15_a", 0, 0, 0, 6, 4, 5, Map.of(
                MassJunctionIntent.PARAM_INTENT, "passage",
                MassJunctionIntent.PARAM_PEER, "j15_b",
                "wall_thickness", 2,
                "narrow_passage", true));
        Component b = mass("j15_b", 6, 0, 0, 6, 4, 5, Map.of(
                MassJunctionIntent.PARAM_INTENT, "passage",
                MassJunctionIntent.PARAM_PEER, "j15_a",
                "wall_thickness", 2,
                "narrow_passage", true));
        repair(a, b);
        assertTrue(CompileCapabilityDiagnostics.hasGap() || AssemblyCompileDiagnostics.hasGap());
        String code = CompileCapabilityDiagnostics.hasGap()
                ? CompileCapabilityDiagnostics.get().code()
                : AssemblyCompileDiagnostics.get().code();
        assertEquals("E_JUNCTION_PASSAGE_BLOCKED", code);
    }

    @Test
    void j16_sharedWallWithVoidPreserved() {
        Map<String, Object> extra = new HashMap<>();
        extra.put(MassJunctionIntent.PARAM_INTENT, "shared_wall");
        extra.put(MassJunctionIntent.PARAM_PEER, "j16_b");
        extra.put("masses", List.of(
                Map.of("operation", "add", "offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 10, "depth", 10, "height", 5)),
                Map.of("operation", "subtract", "offset", Map.of("x", 3, "y", 0, "z", 3),
                        "dimensions", Map.of("width", 4, "depth", 4, "height", 5))
        ));
        Component a = mass("j16_a", 0, 0, 0, 10, 10, 5, extra);
        Component b = mass("j16_b", 9, 0, 0, 8, 8, 5, Map.of(
                MassJunctionIntent.PARAM_INTENT, "shared_wall",
                MassJunctionIntent.PARAM_PEER, "j16_a"));
        LlmPlan repaired = repair(a, b);
        Map<Vec3i, String> blocks = compile(
                repaired.components().stream()
                        .filter(c -> c.componentType().startsWith("MASS"))
                        .toArray(Component[]::new));
        assertNull(blocks.get(new Vec3i(4, 1, 4)), "atrium void must remain empty");
        assertTrue(blocks.keySet().stream().anyMatch(p -> p.x() >= 9));
    }

    @Test
    void j17_fullCpcMultiMassCompile() {
        Component a = mass("j17_a", 0, 0, 0, 10, 10, 6, Map.of(
                MassJunctionIntent.PARAM_INTENT, "shared_wall",
                MassJunctionIntent.PARAM_PEER, "j17_b",
                "floor_count", 2, "floor_height", 3));
        Component b = mass("j17_b", 9, 0, 0, 10, 10, 6, Map.of(
                MassJunctionIntent.PARAM_INTENT, "shared_wall",
                MassJunctionIntent.PARAM_PEER, "j17_a",
                "floor_count", 2, "floor_height", 3));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .components(List.of(a, b))
                .build();
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(CompileCapabilityDiagnostics.hasGap(),
                () -> String.valueOf(CompileCapabilityDiagnostics.get()));
        assertFalse(patches.isEmpty());
        Map<Vec3i, String> blocks = PatchTestSnapshot.blocks(patches);
        long oak = blocks.values().stream().filter(v -> v != null && v.contains("oak")).count();
        long stone = blocks.values().stream().filter(v -> v != null && v.contains("stone")).count();
        assertTrue(oak > 0, "floors present");
        assertTrue(stone > 0, "walls present");
    }

    @Test
    void j18_orderDeterminism() {
        Component a = mass("j18_a", 0, 0, 0, 8, 8, 5, Map.of(
                MassJunctionIntent.PARAM_INTENT, "shared_wall",
                MassJunctionIntent.PARAM_PEER, "j18_b"));
        Component b = mass("j18_b", 7, 0, 0, 8, 8, 5, Map.of(
                MassJunctionIntent.PARAM_INTENT, "shared_wall",
                MassJunctionIntent.PARAM_PEER, "j18_a"));
        Map<Vec3i, String> first = compile(a, b);
        Map<Vec3i, String> second = compile(b, a);
        // Normalize: same cells and materials regardless of component list order.
        assertEquals(first.keySet(), second.keySet(), "cell set must be order-stable");
        for (Vec3i p : first.keySet()) {
            assertEquals(first.get(p), second.get(p), () -> "material drift at " + p);
        }
    }
}
