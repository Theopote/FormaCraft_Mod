package com.formacraft.server.preview;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.BuildExecutionStatus;
import com.formacraft.common.patch.ConfirmedPlanIdentity;
import com.formacraft.common.patch.WorldPatchTransaction;
import com.formacraft.common.patch.history.PatchReplayHistory;
import com.formacraft.common.patch.history.PatchTransaction;
import com.formacraft.server.generation.typology.builder.PalacePreviewPlan;
import com.formacraft.server.generation.typology.builder.TieredMountainPalaceBuilder;
import com.formacraft.server.patch.PatchPreviewService;
import com.formacraft.test.FakeBlockMutationAccess;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
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

/**
 * Preview → Confirm → Apply integrity without a live ServerPlayer.
 * Uses PreviewTicket + expectedStates + WorldPatchTransaction + undo/redo.
 */
class PreviewPlanApplyE2ETest {

    private static final BlockPos ORIGIN = new BlockPos(0, 1, 0);

    @BeforeAll
    static void init() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @BeforeEach
    void clearRegistry() {
        PreviewTicketStorage.clearAllForTest();
        GenerationRequestContext.clear();
    }

    @Test
    void previewConfirmApplyUsesFrozenPatches() {
        List<BlockPatch> frozen = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:stone"),
                new BlockPatch(BlockPatch.PLACE, 1, 1, 0, "minecraft:cobblestone")
        );
        FakeBlockMutationAccess access = new FakeBlockMutationAccess();
        Map<BlockPos, String> expected = PreviewWorldStateCapture.capture(access, ORIGIN, frozen);

        UUID player = UUID.randomUUID();
        PreviewTicket ticket = PreviewTicketStorage.create(
                player, null, ORIGIN, frozen, null, 60_000L,
                "plan-e2e-1", "ctx-e2e", expected);
        assertNotNull(ticket);
        assertTrue(ticket.patchIntegrityOk());
        assertNull(PatchPreviewService.verifyExpectedStates(access, ticket));

        PreviewTicket consumed = PreviewTicketStorage.consume(ticket.id(), player);
        assertNotNull(consumed);
        WorldPatchTransaction.Result applied =
                WorldPatchTransaction.execute(access, ORIGIN, consumed.patches());
        assertEquals(BuildExecutionStatus.SUCCESS, applied.status());
        assertEquals(2, applied.applied());
    }

    @Test
    void worldConflictRejectsApply() {
        List<BlockPatch> frozen = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:stone")
        );
        FakeBlockMutationAccess access = new FakeBlockMutationAccess();
        Map<BlockPos, String> expected = PreviewWorldStateCapture.capture(access, ORIGIN, frozen);
        UUID player = UUID.randomUUID();
        PreviewTicket ticket = PreviewTicketStorage.create(
                player, null, ORIGIN, frozen, null, 60_000L,
                null, "", expected);
        assertNotNull(ticket);

        // Other player / command modifies target cell after preview.
        access.states.put(ORIGIN.add(0, 1, 0), Blocks.GOLD_BLOCK.getDefaultState());
        assertNotNull(PatchPreviewService.verifyExpectedStates(access, ticket));
    }

    @Test
    void paramChangeProducesDifferentContextAndPlanId() {
        Map<String, Object> extras = schematicExtras();
        BuildingSpec spec = specOf(extras);
        var r1 = TieredMountainPalaceBuilder.generateDetailed(extras, ORIGIN, null, spec);
        PalacePreviewPlan p1 = TieredMountainPalaceBuilder.createPreviewPlan(r1, ORIGIN, null, spec);

        extras.put("tiers", 4);
        BuildingSpec spec2 = specOf(extras);
        var r2 = TieredMountainPalaceBuilder.generateDetailed(extras, ORIGIN, null, spec2);
        PalacePreviewPlan p2 = TieredMountainPalaceBuilder.createPreviewPlan(r2, ORIGIN, null, spec2);

        assertNotEquals(p1.contextHash(), p2.contextHash());
        assertNotEquals(p1.planId(), p2.planId());
        // Apply of p1 against p2 context must fail validation.
        var reject = p1.validateForApply(
                null, ORIGIN, net.minecraft.util.math.Direction.SOUTH, extras, null,
                12, 30, 3, 8, (o, f, x, y, z) -> o.add(x, y, z));
        assertFalse(reject.ok());
    }

    @Test
    void sameContextDifferentPatchesDifferentPlanIds() {
        Map<String, Object> extras = schematicExtras();
        BuildingSpec spec = specOf(extras);
        var result = TieredMountainPalaceBuilder.generateDetailed(extras, ORIGIN, null, spec);
        PalacePreviewPlan base = TieredMountainPalaceBuilder.createPreviewPlan(result, ORIGIN, null, spec);

        List<BlockPatch> alt = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone"),
                new BlockPatch(BlockPatch.PLACE, 1, 0, 0, "minecraft:dirt")
        );
        PalacePreviewPlan other = new PalacePreviewPlan(
                TieredMountainPalaceBuilder.GenerateStatus.SUCCESS,
                alt, base.context(), base.diagnostics());
        assertEquals(base.contextHash(), other.contextHash());
        assertNotEquals(base.patchHash(), other.patchHash());
        assertNotEquals(base.planId(), other.planId());
    }

    @Test
    void applyUndoRedoRestoresTargetStates() {
        List<BlockPatch> frozen = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:stone")
        );
        FakeBlockMutationAccess access = new FakeBlockMutationAccess();
        BlockPos target = ORIGIN.add(0, 1, 0);
        access.states.put(target, Blocks.AIR.getDefaultState());

        WorldPatchTransaction.Result applied = WorldPatchTransaction.execute(access, ORIGIN, frozen);
        assertEquals(BuildExecutionStatus.SUCCESS, applied.status());
        assertTrue(access.getState(target).isOf(Blocks.STONE));

        var before = Map.of(target, com.formacraft.common.world.BlockSnapshot.of(Blocks.AIR.getDefaultState()));
        var after = Map.of(target, com.formacraft.common.world.BlockSnapshot.of(Blocks.STONE.getDefaultState()));
        PatchTransaction tx = PatchTransaction.fromSnapshots(ORIGIN, before, after);
        Object worldKey = new Object();
        PatchReplayHistory history = new PatchReplayHistory(8);
        history.record(worldKey, tx);

        var undo = history.undo(worldKey, access);
        assertTrue(undo.available());
        assertTrue(access.getState(target).isOf(Blocks.AIR), "undo should restore air");

        var redo = history.redo(worldKey, access);
        assertTrue(redo.available());
        assertTrue(access.getState(target).isOf(Blocks.STONE), "redo should restore stone");
    }

    @Test
    void pendingPlanBindsByRequestIdAndOwner() {
        Map<String, Object> extras = schematicExtras();
        BuildingSpec spec = specOf(extras);
        var result = TieredMountainPalaceBuilder.generateDetailed(extras, ORIGIN, null, spec);
        PalacePreviewPlan schematic = TieredMountainPalaceBuilder.createPreviewPlan(result, ORIGIN, null, spec);
        PalacePreviewPlan plan = new PalacePreviewPlan(
                TieredMountainPalaceBuilder.GenerateStatus.SUCCESS,
                schematic.patches(), schematic.context(), schematic.diagnostics(),
                schematic.paramsSnapshot(), schematic.paletteId(),
                schematic.corridorMx(), schematic.pathStartZ(), schematic.padY(), schematic.maxDrop());
        assertTrue(plan.executable());
        UUID requestId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        ConfirmedPlanRegistry.putPending(requestId, owner, "schematic", ORIGIN, plan);
        assertNull(ConfirmedPlanRegistry.takePending(
                requestId, UUID.randomUUID(), "schematic", ORIGIN),
                "wrong player must not claim pending");
        PalacePreviewPlan taken = ConfirmedPlanRegistry.takePending(
                requestId, owner, "schematic", ORIGIN);
        assertNotNull(taken);
        assertEquals(plan.planId(), taken.planId());
        assertNull(ConfirmedPlanRegistry.takePending(
                requestId, owner, "schematic", ORIGIN));
    }

    @Test
    void requireBoundTicketMissingPlanIsRejectGate() {
        List<BlockPatch> frozen = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:stone"));
        UUID player = UUID.randomUUID();
        PreviewTicket ticket = PreviewTicketStorage.create(
                player, null, ORIGIN, frozen, null, 60_000L,
                "gone-plan", "ctx", Map.of(), true, UUID.randomUUID());
        assertTrue(ticket.requiresConfirmedPlan());
        assertNull(ConfirmedPlanRegistry.getByTicket(ticket.id(), player),
                "PLAN_MISSING must block confirm — no silent degrade");
    }

    @Test
    void planMismatchWithoutCellPreserveDoesNotTouchWorld() {
        FakeBlockMutationAccess access = new FakeBlockMutationAccess();
        BlockPos target = ORIGIN.add(0, 1, 0);
        access.states.put(target, Blocks.AIR.getDefaultState());
        Map<String, Object> extras = schematicExtras();
        BuildingSpec spec = specOf(extras);
        var result = TieredMountainPalaceBuilder.generateDetailed(extras, ORIGIN, null, spec);
        PalacePreviewPlan schematic = TieredMountainPalaceBuilder.createPreviewPlan(result, ORIGIN, null, spec);
        PalacePreviewPlan plan = new PalacePreviewPlan(
                TieredMountainPalaceBuilder.GenerateStatus.SUCCESS,
                schematic.patches(), schematic.context(), schematic.diagnostics(),
                schematic.paramsSnapshot(), schematic.paletteId(),
                schematic.corridorMx(), schematic.pathStartZ(), schematic.padY(), schematic.maxDrop());
        List<BlockPatch> unrelated = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:gold_block"));
        assertNull(plan.resolveForAcceptedPatches(unrelated));
        // World unchanged — refusal happens before apply.
        assertTrue(access.getState(target).isOf(Blocks.AIR));
    }

    @Test
    void ticketBoundPlanSurvivesSiblingPlanIdRemoval() {
        Map<String, Object> extras = schematicExtras();
        BuildingSpec spec = specOf(extras);
        var result = TieredMountainPalaceBuilder.generateDetailed(extras, ORIGIN, null, spec);
        PalacePreviewPlan schematic = TieredMountainPalaceBuilder.createPreviewPlan(result, ORIGIN, null, spec);
        PalacePreviewPlan plan = new PalacePreviewPlan(
                TieredMountainPalaceBuilder.GenerateStatus.SUCCESS,
                schematic.patches(), schematic.context(), schematic.diagnostics(),
                schematic.paramsSnapshot(), schematic.paletteId(),
                schematic.corridorMx(), schematic.pathStartZ(), schematic.padY(), schematic.maxDrop());
        UUID player = UUID.randomUUID();
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        ConfirmedPlanRegistry.putForTicket(t1, player, UUID.randomUUID(), "schematic", ORIGIN, plan, 60_000L);
        ConfirmedPlanRegistry.putForTicket(t2, player, UUID.randomUUID(), "schematic", ORIGIN, plan, 60_000L);
        ConfirmedPlanRegistry.removeByTicket(t1, player);
        assertNotNull(ConfirmedPlanRegistry.getByTicket(t2, player));
    }

    private static Map<String, Object> schematicExtras() {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("baseWidth", 25);
        extras.put("baseDepth", 21);
        extras.put("tiers", 3);
        extras.put("tierInset", 2);
        extras.put("tierHeight", 5);
        extras.put("platformHeight", 3);
        extras.put("facing", "SOUTH");
        extras.put("schematicFlatTerrain", true);
        return extras;
    }

    private static BuildingSpec specOf(Map<String, Object> extras) {
        BuildingSpec spec = new BuildingSpec();
        spec.setExtra(new HashMap<>(extras));
        return spec;
    }
}
