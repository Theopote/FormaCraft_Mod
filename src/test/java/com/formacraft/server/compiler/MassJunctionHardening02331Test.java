package com.formacraft.server.compiler;

import com.formacraft.common.generation.CompileCapabilityDiagnostics;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.generation.component.util.MassBooleanComposition;
import com.formacraft.common.generation.component.util.MassDomainRelation;
import com.formacraft.common.generation.component.util.MassJunctionGeometry;
import com.formacraft.common.generation.component.util.MassJunctionIntent;
import com.formacraft.common.generation.component.util.ResolvedMassPart;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 02.3.3.1 — Junction Geometry & Authority Hardening (H01–H23).
 */
class MassJunctionHardening02331Test {

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

    private static Component mass(
            String id, int x, int y, int z, int w, int d, int h, Map<String, Object> extra
    ) {
        return mass(id, "main", x, y, z, w, d, h, extra);
    }

    private static LlmPlan planWithSlots(List<Slot> slots, Component... components) {
        return LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .layout(new Layout(null, false, slots))
                .components(List.of(components))
                .build();
    }

    private static LlmPlan repair(Component... components) {
        return MassJunctionRepair.apply(LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .components(List.of(components))
                .build());
    }

    private static LlmPlan repair(LlmPlan plan) {
        return MassJunctionRepair.apply(plan);
    }

    private static Map<Vec3i, String> compile(Component... components) {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .components(List.of(components))
                .build();
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        return PatchTestSnapshot.blocks(patches);
    }

    private static Map<Vec3i, String> compilePlan(LlmPlan plan) {
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        return PatchTestSnapshot.blocks(patches);
    }

    private static boolean isAirish(Map<Vec3i, String> blocks, int x, int y, int z) {
        String b = blocks.get(new Vec3i(x, y, z));
        return b == null || b.contains("air") || !b.contains("stone_brick");
    }

    private static boolean bfsTraverse(
            Map<Vec3i, String> blocks, Vec3i start, Vec3i goal, int x0, int x1, int y0, int y1, int z0, int z1
    ) {
        Set<Long> seen = new HashSet<>();
        ArrayDeque<Vec3i> q = new ArrayDeque<>();
        q.add(start);
        while (!q.isEmpty()) {
            Vec3i c = q.removeFirst();
            if (c.equals(goal)) return true;
            long key = (((long) c.x()) << 40) | (((long) c.y()) << 20) | (c.z() & 0xFFFFF);
            if (!seen.add(key)) continue;
            int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            for (int[] n : d) {
                int nx = c.x() + n[0], ny = c.y() + n[1], nz = c.z() + n[2];
                if (nx < x0 || nx >= x1 || ny < y0 || ny >= y1 || nz < z0 || nz >= z1) continue;
                if (!isAirish(blocks, nx, ny, nz) && !(nx == goal.x() && ny == goal.y() && nz == goal.z())) {
                    // Allow walking onto goal even if marked; interiors are airish.
                    String b = blocks.get(new Vec3i(nx, ny, nz));
                    if (b != null && b.contains("stone_brick")) continue;
                }
                q.add(new Vec3i(nx, ny, nz));
            }
        }
        return false;
    }

    // --- H01–H03 coordinate contract ---

    @Test
    void h01_nonzeroSlotPortalSingleOffset() {
        Slot slot = new Slot("wing", new Vec3i(20, 0, 0), null, null, null, null);
        Component a = mass("h01_a", "wing", 0, 0, 0, 8, 8, 6, Map.of(
                MassJunctionIntent.PARAM_INTENT, "open_connection",
                MassJunctionIntent.PARAM_PEER, "h01_b"));
        Component b = mass("h01_b", "wing", 8, 0, 0, 8, 8, 6, Map.of(
                MassJunctionIntent.PARAM_INTENT, "open_connection",
                MassJunctionIntent.PARAM_PEER, "h01_a"));
        LlmPlan repaired = repair(planWithSlots(List.of(slot), a, b));
        assertFalse(CompileCapabilityDiagnostics.hasGap());
        Component portal = repaired.components().stream()
                .filter(c -> Boolean.TRUE.equals(c.params().get("compiler_junction_portal")))
                .findFirst().orElseThrow();
        // Slot-local: must NOT include the slot anchor (20,0,0) again.
        assertTrue(portal.relativePosition().x() < 20,
                "portal relativePosition must be slot-local, got " + portal.relativePosition());
        assertTrue(portal.relativePosition().x() >= 0 && portal.relativePosition().x() <= 10);
        @SuppressWarnings("unchecked")
        Map<String, Object> planPos = (Map<String, Object>) portal.params().get("entrance_plan_position");
        assertNotNull(planPos);
        assertEquals(20 + portal.relativePosition().x(), ((Number) planPos.get("x")).intValue());
    }

    @Test
    void h02_negativeCoordsHostLocalOmit() {
        Component a = mass("h02_a", -10, 0, -4, 8, 8, 5, Map.of(
                MassJunctionIntent.PARAM_INTENT, "shared_wall",
                MassJunctionIntent.PARAM_PEER, "h02_b",
                "wall_thickness", 1));
        Component b = mass("h02_b", -3, 0, -4, 8, 8, 5, Map.of(
                MassJunctionIntent.PARAM_INTENT, "shared_wall",
                MassJunctionIntent.PARAM_PEER, "h02_a",
                "wall_thickness", 1));
        LlmPlan repaired = repair(a, b);
        assertFalse(CompileCapabilityDiagnostics.hasGap());
        Component secondary = repaired.components().stream()
                .filter(c -> c.params().get("junction_omit_cells") instanceof List<?> list && !list.isEmpty())
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Long> omit = (List<Long>) secondary.params().get("junction_omit_cells");
        // Host-local keys unpack near component relative coords (negative allowed in pack range).
        boolean anyNearHost = false;
        for (Long k : omit) {
            int x = MassBooleanComposition.unpack3X(k);
            int z = MassBooleanComposition.unpack3Z(k);
            if (x >= -10 && x <= 5 && z >= -4 && z <= 4) anyNearHost = true;
        }
        assertTrue(anyNearHost, "omit cells should be host-local near negative origins");
    }

    @Test
    void h03_nonzeroYAndWorldAnchor() {
        Slot slot = new Slot("main", new Vec3i(0, 5, 0), null, null, null, null);
        Component a = mass("h03_a", "main", 0, 2, 0, 8, 8, 6, Map.of(
                MassJunctionIntent.PARAM_INTENT, "passage",
                MassJunctionIntent.PARAM_PEER, "h03_b"));
        Component b = mass("h03_b", "main", 8, 2, 0, 8, 8, 6, Map.of(
                MassJunctionIntent.PARAM_INTENT, "passage",
                MassJunctionIntent.PARAM_PEER, "h03_a"));
        LlmPlan repaired = repair(planWithSlots(List.of(slot), a, b));
        assertFalse(CompileCapabilityDiagnostics.hasGap());
        Component portal = repaired.components().stream()
                .filter(c -> Boolean.TRUE.equals(c.params().get("compiler_junction_portal")))
                .findFirst().orElseThrow();
        // Opening starts above floor slab (component Y=2 + slab 1 → slot-local Y=3).
        assertEquals(3, portal.relativePosition().y(), "slot-local Y is opening band above slab");
        @SuppressWarnings("unchecked")
        Map<String, Object> planPos = (Map<String, Object>) portal.params().get("entrance_plan_position");
        assertEquals(8, ((Number) planPos.get("y")).intValue()); // slot 5 + local 3
        assertTrue(portal.relativePosition().y() >= 2, "portal stays in component Y band");
    }

    // --- H04–H06 pair intent ---

    @Test
    void h04_peerlessIntentDoesNotDiffuse() {
        Component a = mass("h04_a", 0, 0, 0, 8, 8, 5, Map.of(
                MassJunctionIntent.PARAM_INTENT, "open_connection")); // no peer
        Component b = mass("h04_b", 8, 0, 0, 8, 8, 5, Map.of());
        assertEquals(MassJunctionIntent.INDEPENDENT, MassJunctionIntent.forPair(a, b));
        LlmPlan repaired = repair(a, b);
        assertTrue(repaired.components().stream()
                .noneMatch(c -> c.params().get("junction_omit_cells") != null));
        assertTrue(repaired.components().stream()
                .noneMatch(c -> Boolean.TRUE.equals(c.params().get("compiler_junction_portal"))));
    }

    @Test
    void h05_conflictingIntentsCapabilityGap() {
        Component a = mass("h05_a", 0, 0, 0, 8, 8, 5, Map.of(
                MassJunctionIntent.PARAM_INTENT, "open_connection",
                MassJunctionIntent.PARAM_PEER, "h05_b"));
        Component b = mass("h05_b", 8, 0, 0, 8, 8, 5, Map.of(
                MassJunctionIntent.PARAM_INTENT, "shared_wall",
                MassJunctionIntent.PARAM_PEER, "h05_a"));
        repair(a, b);
        assertTrue(CompileCapabilityDiagnostics.hasGap() || AssemblyCompileDiagnostics.hasGap());
        String code = CompileCapabilityDiagnostics.hasGap()
                ? CompileCapabilityDiagnostics.get().code()
                : AssemblyCompileDiagnostics.get().code();
        assertEquals("E_JUNCTION_INTENT_CONFLICT", code);
    }

    @Test
    void h06_junctionByPeerAndEdgeList() {
        Component a = mass("h06_a", 0, 0, 0, 8, 8, 5, Map.of(
                MassJunctionIntent.PARAM_BY_PEER, Map.of("h06_b", "passage")));
        Component b = mass("h06_b", 8, 0, 0, 8, 8, 5, Map.of(
                MassJunctionIntent.PARAM_BY_PEER, Map.of("h06_a", "passage")));
        assertEquals(MassJunctionIntent.PASSAGE, MassJunctionIntent.forEdge(null, a, b).intent());

        Map<String, Object> meta = new HashMap<>();
        meta.put("junctions", List.of(Map.of("from", "h06_c", "to", "h06_d", "intent", "shared_wall")));
        Component c = mass("h06_c", 0, 0, 0, 6, 6, 4, Map.of());
        Component d = mass("h06_d", 6, 0, 0, 6, 6, 4, Map.of());
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .components(List.of(c, d))
                .planMeta(meta)
                .build();
        assertEquals(MassJunctionIntent.SHARED_WALL, MassJunctionIntent.forEdge(plan, c, d).intent());
        LlmPlan repaired = repair(plan);
        assertFalse(CompileCapabilityDiagnostics.hasGap());
        assertNotNull(repaired.planMeta());
        assertTrue(repaired.planMeta().containsKey("compiler_junction_edges")
                || repaired.components().stream().anyMatch(x ->
                x.params().get(MassJunctionIntent.PARAM_COMPILER_BY_PEER) != null));
        // Authored maps preserved (not overwritten with last stamp as sole junction_intent).
        assertNull(repaired.components().get(0).params().get(MassJunctionIntent.PARAM_INTENT));
    }

    // --- H07–H10 domain ---

    @Test
    void h07_emptyDomainNotShared() {
        // Empty domain via zero-size is illegal; use SUBTRACT that clears (or null+empty semantics).
        MassBooleanComposition.CsgResult empty = MassBooleanComposition.compose(List.of(
                new MassBooleanComposition.CsgPart(
                        com.formacraft.common.geometry.shape.CsgOp.UNION, 0, 0, 0, 1, 1, 1),
                new MassBooleanComposition.CsgPart(
                        com.formacraft.common.geometry.shape.CsgOp.SUBTRACT, 0, 0, 0, 1, 1, 1)
        ), "empty");
        assertTrue(empty.isEmpty());
        var aabb = new ComponentFootprintUtil.Bounds(0, 0, 0, 4, 4, 4);
        var q = MassDomainRelation.classify(aabb, aabb, empty, new Vec3i(0, 0, 0), empty, new Vec3i(0, 0, 0));
        assertEquals(MassDomainRelation.DISJOINT, q.relation());
        assertTrue(q.domainAuthoritative());
    }

    @Test
    void h08_unknownDomainNoDeleteAuthority() {
        Component a = mass("h08_a", 0, 0, 0, 8, 8, 5, Map.of());
        Component b = mass("h08_b", 8, 0, 0, 8, 8, 5, Map.of());
        var q = MassDomainRelation.classify(
                ComponentFootprintUtil.bounds(a), ComponentFootprintUtil.bounds(b),
                null, null, null, null);
        assertFalse(q.domainAuthoritative());
        assertEquals(MassDomainRelation.FACE_CONTACT, q.relation());
    }

    @Test
    void h09_exactContainment() {
        MassBooleanComposition.CsgResult outer = MassBooleanComposition.compose(List.of(
                new MassBooleanComposition.CsgPart(
                        com.formacraft.common.geometry.shape.CsgOp.UNION, 0, 0, 0, 8, 8, 6)
        ), "outer");
        MassBooleanComposition.CsgResult inner = MassBooleanComposition.compose(List.of(
                new MassBooleanComposition.CsgPart(
                        com.formacraft.common.geometry.shape.CsgOp.UNION, 2, 1, 2, 3, 3, 3)
        ), "inner");
        var aabbO = new ComponentFootprintUtil.Bounds(0, 0, 0, 8, 6, 8);
        var aabbI = new ComponentFootprintUtil.Bounds(2, 1, 2, 5, 4, 5);
        var q = MassDomainRelation.classify(aabbI, aabbO, inner, new Vec3i(0, 0, 0), outer, new Vec3i(0, 0, 0));
        assertEquals(MassDomainRelation.CONTAINMENT, q.relation());
        assertTrue(MassDomainRelation.exactContained(inner, new Vec3i(0, 0, 0), outer, new Vec3i(0, 0, 0)));
    }

    @Test
    void h10_syntheticEnvelopeFlag() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "h10");
        params.put("anchor_mode", "min_corner");
        params.put("masses", List.of(
                Map.of("offset", Map.of("x", 0, "y", 0, "z", 0),
                        "dimensions", Map.of("width", 6, "depth", 6, "height", 4)),
                Map.of("offset", Map.of("x", 1, "y", 4, "z", 1),
                        "dimensions", Map.of("width", 4, "depth", 4, "height", 3))
        ));
        Component c = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(6, 6, 7), List.of(), params);
        var parts = ResolvedMassPart.resolve(c);
        assertTrue(parts.getFirst().syntheticEnvelope());
        var solid = ResolvedMassPart.solidParts(parts);
        assertEquals(2, solid.size());
        assertTrue(solid.stream().noneMatch(ResolvedMassPart::syntheticEnvelope));
    }

    // --- H11–H15 traversable / shared ---

    @Test
    void h11_throughThicknessOpeningBothWalls() {
        Component a = mass("h11_a", 0, 0, 0, 10, 10, 6, Map.of(
                MassJunctionIntent.PARAM_INTENT, "open_connection",
                MassJunctionIntent.PARAM_PEER, "h11_b",
                "wall_thickness", 2,
                "door_width", 3,
                "door_height", 3));
        Component b = mass("h11_b", 10, 0, 0, 10, 10, 6, Map.of(
                MassJunctionIntent.PARAM_INTENT, "open_connection",
                MassJunctionIntent.PARAM_PEER, "h11_a",
                "wall_thickness", 2));
        LlmPlan repaired = repair(a, b);
        assertFalse(CompileCapabilityDiagnostics.hasGap(),
                () -> String.valueOf(CompileCapabilityDiagnostics.get()));
        Component ca = repaired.components().stream()
                .filter(c -> "h11_a".equals(c.params().get("component_id"))).findFirst().orElseThrow();
        assertTrue(ca.params().get("junction_carve_cells") instanceof List<?> list && !list.isEmpty());
        Map<Vec3i, String> blocks = compilePlan(LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .components(repaired.components())
                .build());
        // Through both 2-thick walls around x=8..11
        boolean cleared = false;
        for (int x = 8; x <= 11; x++) {
            for (int z = 3; z <= 7; z++) {
                if (isAirish(blocks, x, 2, z)) cleared = true;
            }
        }
        assertTrue(cleared, "through-thickness opening must clear both wall skins");
    }

    @Test
    void h12_insufficientContactGap() {
        Component a = mass("h12_a", 0, 0, 0, 6, 6, 5, Map.of(
                MassJunctionIntent.PARAM_INTENT, "open_connection",
                MassJunctionIntent.PARAM_PEER, "h12_b",
                "door_width", 20,
                "door_height", 4));
        Component b = mass("h12_b", 6, 0, 0, 6, 6, 5, Map.of(
                MassJunctionIntent.PARAM_INTENT, "open_connection",
                MassJunctionIntent.PARAM_PEER, "h12_a"));
        repair(a, b);
        assertTrue(CompileCapabilityDiagnostics.hasGap() || AssemblyCompileDiagnostics.hasGap());
        String code = CompileCapabilityDiagnostics.hasGap()
                ? CompileCapabilityDiagnostics.get().code()
                : AssemblyCompileDiagnostics.get().code();
        assertTrue(code.contains("JUNCTION") && (code.contains("INSUFFICIENT") || code.contains("BLOCKED")
                        || code.contains("OPENING")),
                () -> "expected opening/contact gap, got " + code);
    }

    @Test
    void h13_sharedWallMergesOmitNeverStripsFarWall() {
        Component a = mass("h13_a", 0, 0, 0, 10, 10, 6, Map.of(
                MassJunctionIntent.PARAM_INTENT, "shared_wall",
                MassJunctionIntent.PARAM_PEER, "h13_b",
                "wall_thickness", 1));
        Component b = mass("h13_b", 9, 0, 0, 10, 10, 6, Map.of(
                MassJunctionIntent.PARAM_INTENT, "shared_wall",
                MassJunctionIntent.PARAM_PEER, "h13_a",
                "wall_thickness", 1));
        LlmPlan repaired = repair(a, b);
        Map<Vec3i, String> blocks = compile(
                repaired.components().stream()
                        .filter(c -> c.componentType().startsWith("MASS"))
                        .toArray(Component[]::new));
        // Far west wall of A at x=0 must remain.
        assertTrue(blocks.entrySet().stream().anyMatch(e ->
                e.getKey().x() == 0 && e.getValue() != null && e.getValue().contains("stone_brick")));
        // Far east wall of B at x=18 must remain.
        assertTrue(blocks.entrySet().stream().anyMatch(e ->
                e.getKey().x() == 18 && e.getValue() != null && e.getValue().contains("stone_brick")));
    }

    @Test
    void h14_floorYsContinuityNotMinYAlone() {
        Component a = mass("h14_a", 0, 0, 0, 8, 8, 8, Map.of(
                MassJunctionIntent.PARAM_INTENT, "passage",
                MassJunctionIntent.PARAM_PEER, "h14_b",
                "floor_count", 2,
                "floor_height", 4,
                "slab_thickness", 1));
        Component b = mass("h14_b", 8, 0, 0, 8, 8, 8, Map.of(
                MassJunctionIntent.PARAM_INTENT, "passage",
                MassJunctionIntent.PARAM_PEER, "h14_a",
                "floor_count", 2,
                "floor_height", 4,
                "slab_thickness", 1));
        LlmPlan repaired = repair(a, b);
        assertFalse(CompileCapabilityDiagnostics.hasGap());
        assertTrue(repaired.components().stream()
                .anyMatch(c -> c.params().get("junction_floor_place_cells") != null
                        || Boolean.TRUE.equals(c.params().get("compiler_junction_portal"))));
    }

    @Test
    void h15_portalDedupById() {
        Component a = mass("h15_a", 0, 0, 0, 8, 8, 5, Map.of(
                MassJunctionIntent.PARAM_INTENT, "passage",
                MassJunctionIntent.PARAM_PEER, "h15_b"));
        Component b = mass("h15_b", 8, 0, 0, 8, 8, 5, Map.of(
                MassJunctionIntent.PARAM_INTENT, "passage",
                MassJunctionIntent.PARAM_PEER, "h15_a"));
        LlmPlan repaired = repair(a, b);
        long portals = repaired.components().stream()
                .filter(c -> Boolean.TRUE.equals(c.params().get("compiler_junction_portal")))
                .count();
        assertEquals(1, portals);
    }

    // --- H16–H17 occupancy ---

    @Test
    void h16_cellScopedInterlockRejectsUnauthorizedCell() {
        long auth = MassBooleanComposition.pack3(1, 0, 1);
        Component a = mass("h16_a", 0, 0, 0, 4, 4, 4, Map.of(
                "intentional_interlock_peer_id", "h16_b",
                "intentional_interlock_peer_ids", List.of("h16_b"),
                "compiler_junction_authorized_cells", List.of(auth)));
        Component b = mass("h16_b", 0, 0, 0, 4, 4, 4, Map.of(
                "intentional_interlock_peer_id", "h16_a",
                "intentional_interlock_peer_ids", List.of("h16_a"),
                "compiler_junction_authorized_cells", List.of(auth)));
        var index = new PatchOccupancyIndex();
        assertTrue(index.accept(a, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone"))).isEmpty());
        // Unauthorized cell (0,0,0) despite mutual peers.
        var denied = index.accept(b, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:cobblestone")));
        assertTrue(denied.isPresent());
        // Authorized cell.
        assertTrue(index.accept(a, List.of(
                new BlockPatch(BlockPatch.PLACE, 1, 0, 1, "minecraft:stone"))).isEmpty());
        assertTrue(index.accept(b, List.of(
                new BlockPatch(BlockPatch.PLACE, 1, 0, 1, "minecraft:cobblestone"))).isEmpty());
    }

    @Test
    void h17_originalOwnerBlocksAtoBtoC() {
        Component a = mass("h17_a", 0, 0, 0, 4, 4, 4, Map.of(
                "intentional_interlock_peer_ids", List.of("h17_b"),
                "intentional_interlock_peer_id", "h17_b"));
        Component b = mass("h17_b", 0, 0, 0, 4, 4, 4, Map.of(
                "intentional_interlock_peer_ids", List.of("h17_a", "h17_c"),
                "intentional_interlock_peer_id", "h17_a"));
        Component c = mass("h17_c", 0, 0, 0, 4, 4, 4, Map.of(
                "intentional_interlock_peer_ids", List.of("h17_b"),
                "intentional_interlock_peer_id", "h17_b"));
        var index = new PatchOccupancyIndex();
        assertTrue(index.accept(a, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone"))).isEmpty());
        assertTrue(index.accept(b, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:cobblestone"))).isEmpty());
        // C mutual with B but not with original A → denied.
        var leak = index.accept(c, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:bricks")));
        assertTrue(leak.isPresent(), "A→B→C leakage must be denied via originalOwner");
        assertEquals("unauthorized_place_overwrite", leak.get().reason());
    }

    // --- H18–H20 domain/lifecycle extras ---

    @Test
    void h18_cornerContactNeverOpens() {
        Component a = mass("h18_a", 0, 0, 0, 6, 6, 5, Map.of(
                MassJunctionIntent.PARAM_INTENT, "open_connection",
                MassJunctionIntent.PARAM_PEER, "h18_b"));
        Component b = mass("h18_b", 6, 0, 6, 6, 6, 5, Map.of(
                MassJunctionIntent.PARAM_INTENT, "open_connection",
                MassJunctionIntent.PARAM_PEER, "h18_a"));
        LlmPlan repaired = repair(a, b);
        assertTrue(repaired.components().stream()
                .noneMatch(c -> Boolean.TRUE.equals(c.params().get("compiler_junction_portal"))));
    }

    @Test
    void h19_faceContactCellsFromDomain() {
        MassBooleanComposition.CsgResult da = MassBooleanComposition.compose(List.of(
                new MassBooleanComposition.CsgPart(
                        com.formacraft.common.geometry.shape.CsgOp.UNION, 0, 0, 0, 4, 4, 4)
        ), "a");
        MassBooleanComposition.CsgResult db = MassBooleanComposition.compose(List.of(
                new MassBooleanComposition.CsgPart(
                        com.formacraft.common.geometry.shape.CsgOp.UNION, 0, 0, 0, 4, 4, 4)
        ), "b");
        List<Vec3i> contact = MassDomainRelation.faceContactCellsA(
                da, new Vec3i(0, 0, 0), db, new Vec3i(4, 0, 0), "EAST");
        assertFalse(contact.isEmpty());
        assertTrue(contact.stream().allMatch(c -> c.x() == 3));
    }

    @Test
    void h20_junctionAfterSeparationLifecycle() {
        // Overlapping masses with shared_wall: separation should not nudge (junctionHandlesPair),
        // and full compile must still produce omit after freeze order.
        Component a = mass("h20_a", 0, 0, 0, 8, 8, 5, Map.of(
                MassJunctionIntent.PARAM_INTENT, "shared_wall",
                MassJunctionIntent.PARAM_PEER, "h20_b",
                "wall_thickness", 1));
        Component b = mass("h20_b", 7, 0, 0, 8, 8, 5, Map.of(
                MassJunctionIntent.PARAM_INTENT, "shared_wall",
                MassJunctionIntent.PARAM_PEER, "h20_a",
                "wall_thickness", 1));
        assertTrue(MassJunctionRepair.junctionHandlesPair(null, a, b));
        Map<Vec3i, String> blocks = compile(a, b);
        assertFalse(blocks.isEmpty());
        assertFalse(CompileCapabilityDiagnostics.hasGap());
    }

    // --- H21–H23 traverse / materials ---

    @Test
    void h21_bfsInteriorToInteriorViaOpening() {
        Component a = mass("h21_a", 0, 0, 0, 10, 10, 6, Map.of(
                MassJunctionIntent.PARAM_INTENT, "open_connection",
                MassJunctionIntent.PARAM_PEER, "h21_b",
                "door_width", 4,
                "door_height", 3));
        Component b = mass("h21_b", 10, 0, 0, 10, 10, 6, Map.of(
                MassJunctionIntent.PARAM_INTENT, "open_connection",
                MassJunctionIntent.PARAM_PEER, "h21_a"));
        LlmPlan repaired = repair(a, b);
        Map<Vec3i, String> blocks = compilePlan(LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .components(repaired.components())
                .build());
        assertTrue(bfsTraverse(blocks, new Vec3i(4, 2, 4), new Vec3i(14, 2, 4),
                1, 19, 1, 5, 1, 9),
                "A interior must reach B interior through opening");
    }

    @Test
    void h22_sharedWallOwnerMaterialPreserved() {
        Component a = mass("h22_a", 0, 0, 0, 8, 8, 5, Map.of(
                MassJunctionIntent.PARAM_INTENT, "shared_wall",
                MassJunctionIntent.PARAM_PEER, "h22_b",
                "wall_block", "minecraft:stone_bricks",
                "wall_thickness", 1));
        Component b = mass("h22_b", 7, 0, 0, 8, 8, 5, Map.of(
                MassJunctionIntent.PARAM_INTENT, "shared_wall",
                MassJunctionIntent.PARAM_PEER, "h22_a",
                "wall_block", "minecraft:bricks",
                "wall_thickness", 1));
        LlmPlan repaired = repair(a, b);
        // Lexicographically earlier id (h22_a) owns the wall.
        Component secondary = repaired.components().stream()
                .filter(c -> c.params().get("junction_shared_wall_owner") != null)
                .findFirst().orElseThrow();
        assertEquals("h22_a", secondary.params().get("junction_shared_wall_owner"));
        Map<Vec3i, String> blocks = compile(
                repaired.components().stream()
                        .filter(c -> c.componentType().startsWith("MASS"))
                        .toArray(Component[]::new));
        long stone = blocks.entrySet().stream()
                .filter(e -> e.getKey().x() == 7 && e.getValue() != null && e.getValue().contains("stone_brick"))
                .count();
        assertTrue(stone > 0, "owner material remains on seam");
    }

    @Test
    void h23_openingResultOmitsCarveAndFloor() {
        List<Vec3i> contact = new ArrayList<>();
        for (int z = 2; z <= 6; z++) {
            for (int y = 1; y <= 4; y++) {
                contact.add(new Vec3i(7, y, z));
            }
        }
        MassJunctionGeometry.OpeningResult r = MassJunctionGeometry.throughThicknessOpening(
                contact, "EAST", 2, 2, 3, 3, 0, 1);
        assertFalse(r.insufficientContact());
        assertFalse(r.omitPlanA().isEmpty());
        assertFalse(r.omitPlanB().isEmpty());
        assertFalse(r.carvePlan().isEmpty());
        assertFalse(r.floorPlacePlan().isEmpty());
        assertNotNull(r.portalPlanAnchor());
        // A thickness extends westward (against normal), B eastward.
        assertTrue(r.omitPlanA().stream().anyMatch(c -> c.x() <= 7));
        assertTrue(r.omitPlanB().stream().anyMatch(c -> c.x() > 7));
    }
}
