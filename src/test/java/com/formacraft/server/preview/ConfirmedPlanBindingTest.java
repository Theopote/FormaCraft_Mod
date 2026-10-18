package com.formacraft.server.preview;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.generation.typology.builder.PalacePreviewPlan;
import com.formacraft.server.generation.typology.builder.TieredMountainPalaceBuilder;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConfirmedPlanBindingTest {

    private static final BlockPos ORIGIN_A = new BlockPos(0, 1, 0);
    private static final BlockPos ORIGIN_B = new BlockPos(50, 1, 0);

    @BeforeAll
    static void init() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @BeforeEach
    void clear() {
        ConfirmedPlanRegistry.clearAllForTest();
        GenerationRequestIds.clear();
    }

    @Test
    void concurrentIdenticalPatchesDoNotCrossBind() {
        PalacePreviewPlan planA = executablePlan(ORIGIN_A);
        PalacePreviewPlan planB = executablePlan(ORIGIN_B);
        // Same relative patches → same patchHash, different request/origin.
        assertEquals(planA.patchHash(), planB.patchHash());

        UUID reqA = UUID.randomUUID();
        UUID reqB = UUID.randomUUID();
        ConfirmedPlanRegistry.putPending(reqA, "minecraft:overworld", ORIGIN_A, planA);
        ConfirmedPlanRegistry.putPending(reqB, "minecraft:overworld", ORIGIN_B, planB);
        assertEquals(2, ConfirmedPlanRegistry.pendingCountForTest());

        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();
        PalacePreviewPlan takenA = ConfirmedPlanRegistry.takePending(
                reqA, player1, "minecraft:overworld", ORIGIN_A);
        PalacePreviewPlan takenB = ConfirmedPlanRegistry.takePending(
                reqB, player2, "minecraft:overworld", ORIGIN_B);
        assertNotNull(takenA);
        assertNotNull(takenB);
        assertEquals(planA.planId(), takenA.planId());
        assertEquals(planB.planId(), takenB.planId());
        assertNotEquals(takenA.planId(), takenB.planId());
    }

    @Test
    void wrongOriginCannotStealPending() {
        PalacePreviewPlan plan = executablePlan(ORIGIN_A);
        UUID req = UUID.randomUUID();
        ConfirmedPlanRegistry.putPending(req, "minecraft:overworld", ORIGIN_A, plan);
        assertNull(ConfirmedPlanRegistry.takePending(
                req, UUID.randomUUID(), "minecraft:overworld", ORIGIN_B));
        assertNotNull(ConfirmedPlanRegistry.peekPendingForTest(req));
        assertNotNull(ConfirmedPlanRegistry.takePending(
                req, UUID.randomUUID(), "minecraft:overworld", ORIGIN_A));
    }

    @Test
    void samePlayerTwoRequestsStayIsolated() {
        PalacePreviewPlan p1 = executablePlan(ORIGIN_A);
        PalacePreviewPlan p2 = executablePlan(ORIGIN_B);
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();
        ConfirmedPlanRegistry.putPending(r1, "schematic", ORIGIN_A, p1);
        ConfirmedPlanRegistry.putPending(r2, "schematic", ORIGIN_B, p2);
        UUID player = UUID.randomUUID();
        assertEquals(p1.planId(), ConfirmedPlanRegistry.takePending(r1, player, "schematic", ORIGIN_A).planId());
        assertEquals(p2.planId(), ConfirmedPlanRegistry.takePending(r2, player, "schematic", ORIGIN_B).planId());
    }

    @Test
    void requiresConfirmedPlanTicketRejectedWhenPlanMissing() {
        List<BlockPatch> patches = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:stone"));
        UUID player = UUID.randomUUID();
        PreviewTicket ticket = PreviewTicketStorage.create(
                player, null, ORIGIN_A, patches, null, 60_000L,
                "plan-missing-id", "ctx-hash", Map.of(), true, UUID.randomUUID());
        assertNotNull(ticket);
        assertTrue(ticket.requiresConfirmedPlan());
        assertNull(ConfirmedPlanRegistry.get(ticket.planId(), player));
        // Simulate confirm gate: require-bound without registry entry → reject.
        assertTrue(ticket.requiresConfirmedPlan()
                && ConfirmedPlanRegistry.get(ticket.planId(), player) == null);
    }

    @Test
    void ordinaryTicketDoesNotRequirePlan() {
        List<BlockPatch> patches = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:stone"));
        PreviewTicket ticket = PreviewTicketStorage.create(
                UUID.randomUUID(), ORIGIN_A, patches);
        assertNotNull(ticket);
        assertFalse(ticket.requiresConfirmedPlan());
    }

    private static PalacePreviewPlan executablePlan(BlockPos origin) {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("baseWidth", 25);
        extras.put("baseDepth", 21);
        extras.put("tiers", 3);
        extras.put("tierInset", 2);
        extras.put("tierHeight", 5);
        extras.put("platformHeight", 3);
        extras.put("facing", "SOUTH");
        extras.put("schematicFlatTerrain", true);
        BuildingSpec spec = new BuildingSpec();
        spec.setExtra(new HashMap<>(extras));
        var result = TieredMountainPalaceBuilder.generateDetailed(extras, origin, null, spec);
        PalacePreviewPlan schematic =
                TieredMountainPalaceBuilder.createPreviewPlan(result, origin, null, spec);
        return new PalacePreviewPlan(
                TieredMountainPalaceBuilder.GenerateStatus.SUCCESS,
                schematic.patches(),
                schematic.context(),
                schematic.diagnostics(),
                schematic.paramsSnapshot(),
                schematic.paletteId(),
                schematic.corridorMx(),
                schematic.pathStartZ(),
                schematic.padY(),
                schematic.maxDrop());
    }
}
