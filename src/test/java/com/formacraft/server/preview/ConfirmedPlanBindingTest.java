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
        PreviewTicketStorage.clearAllForTest();
        GenerationRequestContext.clear();
    }

    @Test
    void concurrentIdenticalPatchesDoNotCrossBind() {
        PalacePreviewPlan planA = executablePlan(ORIGIN_A);
        PalacePreviewPlan planB = executablePlan(ORIGIN_B);
        assertEquals(planA.patchHash(), planB.patchHash());

        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();
        UUID reqA = UUID.randomUUID();
        UUID reqB = UUID.randomUUID();
        ConfirmedPlanRegistry.putPending(reqA, player1, "minecraft:overworld", ORIGIN_A, planA);
        ConfirmedPlanRegistry.putPending(reqB, player2, "minecraft:overworld", ORIGIN_B, planB);
        assertEquals(2, ConfirmedPlanRegistry.pendingCountForTest());

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
        UUID owner = UUID.randomUUID();
        UUID req = UUID.randomUUID();
        ConfirmedPlanRegistry.putPending(req, owner, "minecraft:overworld", ORIGIN_A, plan);
        assertNull(ConfirmedPlanRegistry.takePending(
                req, owner, "minecraft:overworld", ORIGIN_B));
        assertNotNull(ConfirmedPlanRegistry.peekPendingForTest(req));
        assertNotNull(ConfirmedPlanRegistry.takePending(
                req, owner, "minecraft:overworld", ORIGIN_A));
    }

    @Test
    void wrongPlayerCannotTakePending() {
        PalacePreviewPlan plan = executablePlan(ORIGIN_A);
        UUID owner = UUID.randomUUID();
        UUID interloper = UUID.randomUUID();
        UUID req = UUID.randomUUID();
        ConfirmedPlanRegistry.putPending(req, owner, "minecraft:overworld", ORIGIN_A, plan);
        assertNull(ConfirmedPlanRegistry.takePending(
                req, interloper, "minecraft:overworld", ORIGIN_A));
        assertNotNull(ConfirmedPlanRegistry.peekPendingForTest(req),
                "ownership mismatch must not consume pending");
        assertNotNull(ConfirmedPlanRegistry.takePending(
                req, owner, "minecraft:overworld", ORIGIN_A));
    }

    @Test
    void samePlayerTwoRequestsStayIsolated() {
        PalacePreviewPlan p1 = executablePlan(ORIGIN_A);
        PalacePreviewPlan p2 = executablePlan(ORIGIN_B);
        UUID player = UUID.randomUUID();
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();
        ConfirmedPlanRegistry.putPending(r1, player, "schematic", ORIGIN_A, p1);
        ConfirmedPlanRegistry.putPending(r2, player, "schematic", ORIGIN_B, p2);
        assertEquals(p1.planId(), ConfirmedPlanRegistry.takePending(r1, player, "schematic", ORIGIN_A).planId());
        assertEquals(p2.planId(), ConfirmedPlanRegistry.takePending(r2, player, "schematic", ORIGIN_B).planId());
    }

    @Test
    void twoPlayersSamePlanIdDoNotOverwrite() {
        PalacePreviewPlan plan = executablePlan(ORIGIN_A);
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        UUID ticketA = UUID.randomUUID();
        UUID ticketB = UUID.randomUUID();

        ConfirmedPlanRegistry.putForTicket(
                ticketA, playerA, UUID.randomUUID(), "schematic", ORIGIN_A, plan, 60_000L);
        ConfirmedPlanRegistry.putForTicket(
                ticketB, playerB, UUID.randomUUID(), "schematic", ORIGIN_A, plan, 60_000L);

        assertEquals(2, ConfirmedPlanRegistry.boundTicketCountForTest());
        assertSame(plan, ConfirmedPlanRegistry.getByTicket(ticketA, playerA));
        assertSame(plan, ConfirmedPlanRegistry.getByTicket(ticketB, playerB));
        assertNull(ConfirmedPlanRegistry.getByTicket(ticketA, playerB));
        assertNull(ConfirmedPlanRegistry.getByTicket(ticketB, playerA));

        ConfirmedPlanRegistry.removeByTicket(ticketA, playerA);
        assertEquals(1, ConfirmedPlanRegistry.boundTicketCountForTest());
        assertSame(plan, ConfirmedPlanRegistry.getByTicket(ticketB, playerB),
                "removing A's ticket must not touch B's bound plan");
    }

    @Test
    void samePlayerTwoTicketsSamePlanIdIndependentLifecycle() {
        PalacePreviewPlan plan = executablePlan(ORIGIN_A);
        UUID player = UUID.randomUUID();
        UUID ticket1 = UUID.randomUUID();
        UUID ticket2 = UUID.randomUUID();

        ConfirmedPlanRegistry.putForTicket(
                ticket1, player, UUID.randomUUID(), "schematic", ORIGIN_A, plan, 60_000L);
        ConfirmedPlanRegistry.putForTicket(
                ticket2, player, UUID.randomUUID(), "schematic", ORIGIN_A, plan, 60_000L);
        assertEquals(2, ConfirmedPlanRegistry.boundTicketCountForTest());

        ConfirmedPlanRegistry.removeByTicket(ticket1, player);
        assertNull(ConfirmedPlanRegistry.getByTicket(ticket1, player));
        assertSame(plan, ConfirmedPlanRegistry.getByTicket(ticket2, player),
                "confirming/clearing ticket1 must leave ticket2's plan");
    }

    @Test
    void cancelPlayerClearsOnlyOwnTicketsAndPlans() {
        PalacePreviewPlan plan = executablePlan(ORIGIN_A);
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        List<BlockPatch> patches = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:stone"));

        PreviewTicket tA = PreviewTicketStorage.create(
                playerA, null, ORIGIN_A, patches, null, 60_000L,
                plan.planId(), plan.contextHash(), Map.of(), true, UUID.randomUUID());
        PreviewTicket tB = PreviewTicketStorage.create(
                playerB, null, ORIGIN_A, patches, null, 60_000L,
                plan.planId(), plan.contextHash(), Map.of(), true, UUID.randomUUID());
        assertNotNull(tA);
        assertNotNull(tB);
        ConfirmedPlanRegistry.putForTicket(
                tA.id(), playerA, tA.generationRequestId(), "schematic", ORIGIN_A, plan, 60_000L);
        ConfirmedPlanRegistry.putForTicket(
                tB.id(), playerB, tB.generationRequestId(), "schematic", ORIGIN_A, plan, 60_000L);

        PreviewTicketStorage.clearPlayer(playerA);
        assertNull(PreviewTicketStorage.get(tA.id()));
        assertNull(ConfirmedPlanRegistry.getByTicket(tA.id(), playerA));
        assertNotNull(PreviewTicketStorage.get(tB.id()));
        assertSame(plan, ConfirmedPlanRegistry.getByTicket(tB.id(), playerB));
    }

    @Test
    void expiredTicketClearsBoundPlan() throws InterruptedException {
        PalacePreviewPlan plan = executablePlan(ORIGIN_A);
        UUID player = UUID.randomUUID();
        List<BlockPatch> patches = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:stone"));
        // Storage clamps TTL to ≥1000ms.
        PreviewTicket ticket = PreviewTicketStorage.create(
                player, null, ORIGIN_A, patches, null, 1_000L,
                plan.planId(), plan.contextHash(), Map.of(), true, UUID.randomUUID());
        assertNotNull(ticket);
        ConfirmedPlanRegistry.putForTicket(
                ticket.id(), player, ticket.generationRequestId(), "schematic", ORIGIN_A, plan, 1_000L);
        Thread.sleep(1_100L);
        assertNull(PreviewTicketStorage.get(ticket.id()));
        assertNull(ConfirmedPlanRegistry.getByTicket(ticket.id(), player));
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
        assertNull(ConfirmedPlanRegistry.getByTicket(ticket.id(), player));
        assertTrue(ticket.requiresConfirmedPlan()
                && ConfirmedPlanRegistry.getByTicket(ticket.id(), player) == null);
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

    @Test
    void requestContextClearedAfterException() {
        UUID player = UUID.randomUUID();
        try {
            GenerationRequestContext.begin(player, "schematic", ORIGIN_A);
            assertNotNull(GenerationRequestContext.current());
            throw new RuntimeException("boom");
        } catch (RuntimeException ignored) {
            // simulate finally
        } finally {
            GenerationRequestContext.clear();
        }
        assertNull(GenerationRequestContext.current(), "request context must not leak after exception");
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
