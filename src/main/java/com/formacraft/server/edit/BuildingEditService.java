package com.formacraft.server.edit;

import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.BlockPatchTargetResolver;
import com.formacraft.server.memory.MemoryManager;
import com.formacraft.server.memory.MemoryUpdateResult;
import com.formacraft.server.memory.ProjectMemory;
import net.minecraft.block.BlockState;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 1 orchestration: resolve building → load roof index → compile material replace →
 * validate scope. Preview/Apply still go through existing PatchPreviewService.
 */
public final class BuildingEditService {
    public record PreparedEdit(
            BuildingInstance building,
            BuildingEditScope scope,
            List<BlockPatch> patches,
            BuildingEditValidation validation,
            List<String> refusals
    ) {
        public PreparedEdit {
            patches = patches == null ? List.of() : List.copyOf(patches);
            refusals = refusals == null ? List.of() : List.copyOf(refusals);
        }

        public boolean ok() {
            return refusals.isEmpty()
                    && validation != null
                    && validation.accepted()
                    && !patches.isEmpty()
                    && building != null
                    && scope != null;
        }
    }

    public record BeginTxnResult(boolean ok, String message, BuildingEditTransaction transaction) {
        public static BeginTxnResult pass(BuildingEditTransaction txn) {
            return new BeginTxnResult(true, null, txn);
        }

        public static BeginTxnResult fail(String message) {
            return new BeginTxnResult(false, message == null ? "txn_prepare_failed" : message, null);
        }
    }

    public record RepairResult(boolean ok, String message) {
        public static RepairResult pass() { return new RepairResult(true, null); }
        public static RepairResult fail(String message) {
            return new RepairResult(false, message == null ? "repair_failed" : message);
        }
    }

    private final MemoryManager memoryManager;
    private final BuildingTargetResolver targetResolver;

    public BuildingEditService(MemoryManager memoryManager) {
        this.memoryManager = Objects.requireNonNull(memoryManager, "memoryManager");
        this.targetResolver = new BuildingTargetResolver(memoryManager);
    }

    public BuildingTargetResolver targets() {
        return targetResolver;
    }

    /**
     * Prepare a roof-material replace. {@code liveWorldStates} should be captured by the server
     * for expected-state conflict detection (preview/confirm).
     */
    public PreparedEdit prepareRoofMaterialReplace(
            BuildingTargetResolver.Query targetQuery,
            String materialIntent,
            Map<BlockPos, String> liveWorldStates,
            Set<BlockPos> extraProtectedCells
    ) {
        BuildingTargetResolver.Result target = targetResolver.resolve(targetQuery);
        if (!target.ok()) {
            return new PreparedEdit(null, null, List.of(), null,
                    List.of(BuildingTargetResolver.refusalMessage(target).orElse("目标建筑解析失败")));
        }
        BuildingInstance building = target.instance();
        ProjectMemory memory = memoryManager.getMemory(building.buildingId().toString());
        if (memory == null) {
            return new PreparedEdit(building, null, List.of(), null,
                    List.of("指定建筑未登记在 Memory 中，拒绝猜测性全局修改。"));
        }
        if (BuildingMemoryKeys.isMemorySyncBlocked(memory)
                || BuildingEditTransactionStore.findOpenByBuilding(building.buildingId()) != null) {
            return new PreparedEdit(building, null, List.of(), null,
                    List.of("该建筑存在未完成的编辑事务（RECOVERY_REQUIRED / EDIT_MEMORY_SYNC_FAILED），"
                            + "增量编辑已暂停；请 Undo 或修复 Memory 后再试。"));
        }

        BuildingPartIndex index = BuildingPartIndex.fromMemory(memory);
        if (index.roof().isEmpty()) {
            return new PreparedEdit(building, null, List.of(), null,
                    List.of("无法安全定位屋顶：该建筑没有已登记的屋顶部位索引，禁止退化为整栋重建。"));
        }

        Set<BlockPos> protectedCells = new LinkedHashSet<>();
        if (extraProtectedCells != null) protectedCells.addAll(extraProtectedCells);

        BuildingEditScope scope = BuildingEditScope.replaceRoofMaterial(
                building, index.roof(), index.roof().recordedStates(), protectedCells);

        RoofMaterialReplaceCompiler.CompileResult compiled = RoofMaterialReplaceCompiler.compile(
                building.origin(), index.roof(), materialIntent, liveWorldStates);
        if (!compiled.ok()) {
            return new PreparedEdit(building, scope, List.of(), null, compiled.refusals());
        }

        BuildingEditValidation validation = BuildingEditScopeValidator.validate(
                scope,
                building.origin(),
                compiled.patches(),
                BuildingMemoryKeys.revisionOf(memory),
                liveWorldStates == null ? Map.of() : liveWorldStates);
        if (!validation.accepted()) {
            return new PreparedEdit(building, scope, compiled.patches(), validation, validation.conflicts());
        }
        return new PreparedEdit(building, scope, compiled.patches(), validation, List.of());
    }

    /**
     * Persist PREPARED transaction before world write. Failure aborts Confirm — no world mutation.
     */
    public BeginTxnResult beginTransaction(BuildingEditPendingRegistry.Pending pending) {
        if (pending == null) return BeginTxnResult.fail("pending_missing");
        if (BuildingEditTransactionStore.findOpenByBuilding(pending.buildingId()) != null) {
            return BeginTxnResult.fail("building_has_open_transaction");
        }
        long now = System.currentTimeMillis();
        BuildingEditTransaction txn = new BuildingEditTransaction(
                UUID.randomUUID(),
                pending.ticketId(),
                pending.playerId(),
                pending.buildingId(),
                pending.dimension(),
                pending.baseRevision(),
                BuildingEditTransaction.Operation.REPLACE_ROOF_MATERIAL,
                pending.expectedWorldStates(),
                pending.newRoofStates(),
                BuildingEditTransaction.Status.PREPARED,
                now,
                now);
        if (!BuildingEditTransactionStore.putPrepared(txn)) {
            return BeginTxnResult.fail("txn_persist_failed");
        }
        return BeginTxnResult.pass(txn);
    }

    public boolean markWorldApplied(UUID transactionId) {
        return BuildingEditTransactionStore.updateStatus(
                transactionId, BuildingEditTransaction.Status.WORLD_APPLIED);
    }

    public boolean markRecoveryRequired(UUID transactionId) {
        return BuildingEditTransactionStore.updateStatus(
                transactionId, BuildingEditTransaction.Status.RECOVERY_REQUIRED);
    }

    public boolean markMemoryCommitted(UUID transactionId) {
        return BuildingEditTransactionStore.updateStatus(
                transactionId, BuildingEditTransaction.Status.MEMORY_COMMITTED);
    }

    public boolean abortTransaction(UUID transactionId) {
        return BuildingEditTransactionStore.updateStatus(
                transactionId, BuildingEditTransaction.Status.ABORTED);
    }

    /**
     * After a successful Apply of a Phase-1 roof edit: bump revision and refresh recorded roof states.
     * Uses {@link MemoryManager#updateMemoryStrict} — persist failure returns false.
     */
    public boolean commitSuccessfulRoofEdit(
            UUID buildingId,
            Identifier dimension,
            long expectedBaseRevision,
            Map<BlockPos, String> newRoofStates
    ) {
        if (buildingId == null || dimension == null) return false;
        ProjectMemory memory = memoryManager.getMemory(buildingId.toString());
        BuildingInstance instance = BuildingInstance.fromMemory(memory);
        if (instance == null) return false;
        if (!instance.dimension().equals(dimension)) return false;
        long current = BuildingMemoryKeys.revisionOf(memory);
        // Idempotent: already committed this txn (revision advanced + states applied).
        if (current == expectedBaseRevision + 1L) {
            BuildingPartIndex index = BuildingPartIndex.fromMemory(memory);
            if (roofStatesMatch(index.roof().recordedStates(), newRoofStates)) {
                BuildingMemoryKeys.clearMemorySyncFailed(memory);
                return true;
            }
        }
        if (current != expectedBaseRevision) return false;

        BuildingPartIndex index = BuildingPartIndex.fromMemory(memory);
        Map<BlockPos, String> previousStates = new LinkedHashMap<>(index.roof().recordedStates());
        List<BlockPos> previousCells = List.copyOf(index.roof().cells());
        Map<BlockPos, String> states = new LinkedHashMap<>(previousStates);
        if (newRoofStates != null) {
            for (var e : newRoofStates.entrySet()) {
                if (e.getKey() != null && index.roof().cells().contains(e.getKey()) && e.getValue() != null) {
                    states.put(e.getKey(), e.getValue());
                }
            }
        }
        BuildingPartIndex.ofRoofCells(List.copyOf(index.roof().cells()), states).persistTo(memory);
        BuildingMemoryKeys.bumpRevision(memory);
        BuildingMemoryKeys.clearMemorySyncFailed(memory);
        MemoryUpdateResult result = memoryManager.updateMemoryStrict(memory);
        if (!result.ok()) {
            // Disk unchanged; restore in-heap object so the same baseRevision can be retried.
            BuildingPartIndex.ofRoofCells(previousCells, previousStates).persistTo(memory);
            BuildingMemoryKeys.setRevision(memory, expectedBaseRevision);
            return false;
        }
        return true;
    }

    /**
     * World Apply succeeded but Memory commit failed: mark building blocked and txn RECOVERY_REQUIRED.
     * Best-effort ProjectMemory flag; durable truth is the transaction store.
     */
    public void markMemorySyncFailed(
            UUID buildingId,
            Identifier dimension,
            long expectedBaseRevision,
            Map<BlockPos, String> newRoofStates
    ) {
        if (buildingId == null) return;
        ProjectMemory memory = memoryManager.getMemory(buildingId.toString());
        if (memory != null) {
            BuildingMemoryKeys.markMemorySyncFailed(memory, dimension, expectedBaseRevision, newRoofStates);
            memoryManager.updateMemoryStrict(memory); // best-effort; ignore failure
        }
        BuildingEditTransaction open = BuildingEditTransactionStore.findOpenByBuilding(buildingId);
        if (open != null) {
            BuildingEditTransactionStore.updateStatus(
                    open.transactionId(), BuildingEditTransaction.Status.RECOVERY_REQUIRED);
        }
    }

    /**
     * Repair after EDIT_MEMORY_SYNC_FAILED / RECOVERY_REQUIRED.
     * Requires live world states: must match target (commit metadata) or expected (treat as undone).
     * Never invents Memory from stale roof records without world proof.
     */
    public RepairResult repairMemorySyncWithLiveWorld(
            UUID buildingId,
            Identifier dimension,
            Map<BlockPos, String> liveWorldStates
    ) {
        if (buildingId == null || dimension == null) {
            return RepairResult.fail("invalid_args");
        }
        if (liveWorldStates == null) {
            return RepairResult.fail("live_world_states_required");
        }
        BuildingEditTransaction txn = BuildingEditTransactionStore.findOpenByBuilding(buildingId);
        ProjectMemory memory = memoryManager.getMemory(buildingId.toString());
        if (memory == null) return RepairResult.fail("memory_missing");
        if (!BuildingMemoryKeys.isMemorySyncBlocked(memory) && (txn == null || !txn.isOpen())) {
            return RepairResult.fail("no_recovery_required");
        }

        Map<BlockPos, String> targets = txn != null
                ? txn.targetWorldStates()
                : BuildingMemoryKeys.memorySyncPendingRoofStates(memory);
        Map<BlockPos, String> expected = txn != null
                ? txn.expectedWorldStates()
                : Map.of();
        long base = txn != null
                ? txn.baseRevision()
                : BuildingMemoryKeys.memorySyncBaseRevision(memory);
        if (base < 0L) base = BuildingMemoryKeys.revisionOf(memory);

        if (statesEqual(liveWorldStates, targets)) {
            boolean committed = commitSuccessfulRoofEdit(buildingId, dimension, base, targets);
            if (!committed) return RepairResult.fail("memory_commit_failed");
            if (txn != null) {
                BuildingEditTransactionStore.updateStatus(
                        txn.transactionId(), BuildingEditTransaction.Status.MEMORY_COMMITTED);
            }
            return RepairResult.pass();
        }
        if (!expected.isEmpty() && statesEqual(liveWorldStates, expected)) {
            // World matches pre-edit — treat as undone; clear block without bumping revision.
            BuildingMemoryKeys.clearMemorySyncFailed(memory);
            MemoryUpdateResult cleared = memoryManager.updateMemoryStrict(memory);
            if (!cleared.ok()) return RepairResult.fail("clear_sync_flag_failed");
            if (txn != null) {
                BuildingEditTransactionStore.updateStatus(
                        txn.transactionId(), BuildingEditTransaction.Status.ABORTED);
            }
            return RepairResult.pass();
        }
        return RepairResult.fail("world_state_mismatch_keep_recovery_required");
    }

    /** Attach a roof part index when a building is first registered (compile provenance). */
    public static void attachRoofIndex(ProjectMemory memory, BuildingPartIndex index) {
        if (memory == null || index == null || index.roof().isEmpty()) return;
        index.persistTo(memory);
        if (!memory.getMetadata().containsKey(BuildingMemoryKeys.REVISION)) {
            BuildingMemoryKeys.setRevision(memory, 0L);
        }
    }

    /**
     * Bind a prepared roof edit to a PreviewTicket. Call after {@code PatchPreviewService.issuePreview}.
     * Memory revision advances only when that ticket Apply returns SUCCESS.
     */
    public static void bindPendingToTicket(
            UUID ticketId,
            UUID playerId,
            long expiresAtMillis,
            PreparedEdit prepared
    ) {
        if (ticketId == null || playerId == null || prepared == null || !prepared.ok()) return;
        Map<BlockPos, String> newStates = new LinkedHashMap<>();
        BlockPos origin = prepared.building().origin();
        for (BlockPatch patch : prepared.patches()) {
            if (patch == null || patch.targetBlock() == null) continue;
            newStates.put(origin.add(patch.dx(), patch.dy(), patch.dz()).toImmutable(), patch.targetBlock());
        }
        BuildingEditPendingRegistry.put(new BuildingEditPendingRegistry.Pending(
                ticketId,
                playerId,
                prepared.building().buildingId(),
                prepared.building().dimension(),
                prepared.scope().baseRevision(),
                prepared.scope().allowedCells(),
                prepared.scope().expectedWorldStates(),
                newStates,
                expiresAtMillis));
    }

    private static boolean roofStatesMatch(Map<BlockPos, String> recorded, Map<BlockPos, String> expected) {
        if (expected == null || expected.isEmpty()) return true;
        for (var e : expected.entrySet()) {
            if (!statesMatch(recorded.get(e.getKey()), e.getValue())) return false;
        }
        return true;
    }

    private static boolean statesEqual(Map<BlockPos, String> live, Map<BlockPos, String> reference) {
        if (reference == null || reference.isEmpty()) return false;
        for (var e : reference.entrySet()) {
            String actual = live.get(e.getKey());
            if (!statesMatch(actual, e.getValue())) return false;
        }
        return true;
    }

    private static boolean statesMatch(String a, String b) {
        if (Objects.equals(a, b)) return true;
        if (a == null || b == null) return false;
        BlockState sa = BlockPatchTargetResolver.parse(a);
        BlockState sb = BlockPatchTargetResolver.parse(b);
        if (sa == null || sb == null) return false;
        return sa.equals(sb)
                || Objects.equals(BlockStateStringUtil.fromState(sa), BlockStateStringUtil.fromState(sb));
    }
}
