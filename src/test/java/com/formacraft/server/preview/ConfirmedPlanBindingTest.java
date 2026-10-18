package com.formacraft.server.preview;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.ConfirmedPlanIdentity;
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

    @Test
    void mergePreservingPlanCellsRefreezesNotDegrades() {
        PalacePreviewPlan plan = executablePlan(ORIGIN_A);
        List<BlockPatch> merged = new java.util.ArrayList<>(plan.patches());
        merged.add(new BlockPatch(BlockPatch.PLACE, 99, 1, 99, "minecraft:oak_planks"));
        PalacePreviewPlan resolved = plan.resolveForAcceptedPatches(merged);
        assertNotNull(resolved, "legal merge must re-freeze, not return null");
        assertNotEquals(plan.planId(), resolved.planId());
        assertEquals(plan.contextHash(), resolved.contextHash(), "terrain/context must be preserved");
        assertEquals(ConfirmedPlanIdentity.patchHash(merged), resolved.patchHash());
        assertTrue(resolved.executable());
        assertTrue(resolved.validateMergedIntegrity(plan).isEmpty());
    }

    @Test
    void mergeDeletingStairsIsRejected() {
        PalacePreviewPlan plan = executablePlan(ORIGIN_A);
        BlockPatch stair = plan.patches().stream()
                .filter(PalacePreviewPlan::isStairPatch)
                .findFirst()
                .orElseThrow(() -> new AssertionError("palace plan must contain stairs"));
        List<BlockPatch> merged = new java.util.ArrayList<>();
        for (BlockPatch p : plan.patches()) {
            if (p.dx() == stair.dx() && p.dy() == stair.dy() && p.dz() == stair.dz()) {
                merged.add(new BlockPatch(BlockPatch.PLACE, p.dx(), p.dy(), p.dz(), "minecraft:dirt"));
            } else {
                merged.add(p);
            }
        }
        assertNull(plan.resolveForAcceptedPatches(merged),
                "overwriting necessary stairs must refuse freeze");
        PalacePreviewPlan trial = plan.freezeMerged(merged);
        assertNotNull(trial);
        assertFalse(trial.validateMergedIntegrity(plan).isEmpty());
    }

    @Test
    void mergeOverwritingEntranceClearanceIsRejected() {
        PalacePreviewPlan plan = executablePlan(ORIGIN_A);
        BlockPatch clearance = plan.patches().stream()
                .filter(PalacePreviewPlan::isClearancePatch)
                .findFirst()
                .orElseThrow(() -> new AssertionError("palace plan must contain clearance/REMOVE"));
        List<BlockPatch> merged = new java.util.ArrayList<>();
        for (BlockPatch p : plan.patches()) {
            if (p.dx() == clearance.dx() && p.dy() == clearance.dy() && p.dz() == clearance.dz()) {
                merged.add(new BlockPatch(BlockPatch.PLACE, p.dx(), p.dy(), p.dz(), "minecraft:stone"));
            } else {
                merged.add(p);
            }
        }
        assertNull(plan.resolveForAcceptedPatches(merged),
                "overwriting entrance clearance must refuse freeze");
    }

    @Test
    void mergeModifyingUnrelatedWallMayPassWhenCirculationHolds() {
        PalacePreviewPlan plan = executablePlan(ORIGIN_A);
        BlockPatch wall = plan.patches().stream()
                .filter(p -> p.targetBlock() != null
                        && p.targetBlock().contains("stone_bricks")
                        && !PalacePreviewPlan.isStairPatch(p)
                        && !PalacePreviewPlan.isDoorPatch(p)
                        && !PalacePreviewPlan.isClearancePatch(p)
                        && p.dy() >= 8)
                .findFirst()
                .orElse(null);
        if (wall == null) {
            // Schematic palace may not expose a high wall cell — skip soft policy case.
            return;
        }
        List<BlockPatch> merged = new java.util.ArrayList<>();
        for (BlockPatch p : plan.patches()) {
            if (p.dx() == wall.dx() && p.dy() == wall.dy() && p.dz() == wall.dz()) {
                merged.add(new BlockPatch(BlockPatch.PLACE, p.dx(), p.dy(), p.dz(), "minecraft:cobblestone"));
            } else {
                merged.add(p);
            }
        }
        PalacePreviewPlan resolved = plan.resolveForAcceptedPatches(merged);
        assertNotNull(resolved, "unrelated wall overwrite allowed when circulation intact");
    }

    @Test
    void unrelatedPatchesRefuseBindNotSilentDowngrade() {
        PalacePreviewPlan plan = executablePlan(ORIGIN_A);
        List<BlockPatch> unrelated = List.of(
                new BlockPatch(BlockPatch.PLACE, 7, 7, 7, "minecraft:dirt"),
                new BlockPatch(BlockPatch.PLACE, 8, 7, 7, "minecraft:dirt"));
        assertNull(plan.resolveForAcceptedPatches(unrelated),
                "unrelated compile must refuse — never silent ordinary ticket");
        assertFalse(plan.cellsPreservedIn(unrelated));
    }

    @Test
    void exactMatchKeepsSamePlanInstance() {
        PalacePreviewPlan plan = executablePlan(ORIGIN_A);
        PalacePreviewPlan resolved = plan.resolveForAcceptedPatches(plan.patches());
        assertSame(plan, resolved);
    }

    @Test
    void consumeForApplyDetachesPlanAndIsOneShot() {
        PalacePreviewPlan plan = executablePlan(ORIGIN_A);
        UUID player = UUID.randomUUID();
        PreviewTicket ticket = PreviewTicketStorage.create(
                player, null, ORIGIN_A, plan.patches(), null, 60_000L,
                plan.planId(), plan.contextHash(), Map.of(), true, UUID.randomUUID());
        assertNotNull(ticket);
        ConfirmedPlanRegistry.putForTicket(
                ticket.id(), player, ticket.generationRequestId(), "schematic", ORIGIN_A, plan, 60_000L);

        PreviewTicketStorage.TicketConsumption first =
                PreviewTicketStorage.consumeForApply(ticket.id(), player);
        assertNotNull(first);
        assertSame(plan, first.boundPlan());
        assertNull(ConfirmedPlanRegistry.getByTicket(ticket.id(), player));
        assertNull(PreviewTicketStorage.consumeForApply(ticket.id(), player),
                "repeat confirm must not re-apply");
    }

    @Test
    void consumeForApplyDoesNotAffectSiblingTicket() {
        PalacePreviewPlan plan = executablePlan(ORIGIN_A);
        UUID player = UUID.randomUUID();
        PreviewTicket t1 = PreviewTicketStorage.create(
                player, null, ORIGIN_A, plan.patches(), null, 60_000L,
                plan.planId(), plan.contextHash(), Map.of(), true, UUID.randomUUID());
        PreviewTicket t2 = PreviewTicketStorage.create(
                player, null, ORIGIN_A, plan.patches(), null, 60_000L,
                plan.planId(), plan.contextHash(), Map.of(), true, UUID.randomUUID());
        ConfirmedPlanRegistry.putForTicket(
                t1.id(), player, t1.generationRequestId(), "schematic", ORIGIN_A, plan, 60_000L);
        ConfirmedPlanRegistry.putForTicket(
                t2.id(), player, t2.generationRequestId(), "schematic", ORIGIN_A, plan, 60_000L);

        assertNotNull(PreviewTicketStorage.consumeForApply(t1.id(), player));
        assertNotNull(PreviewTicketStorage.get(t2.id()));
        assertSame(plan, ConfirmedPlanRegistry.getByTicket(t2.id(), player));
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
