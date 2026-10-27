package com.formacraft.server.edit;

import com.formacraft.FormacraftMod;
import com.formacraft.server.memory.MemoryManager;
import com.formacraft.server.memory.ProjectMemory;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-authoritative recovery for unfinished building-edit transactions.
 * Order: load txn → resolve dimension → capture live world → classify → then Memory/txn finalize.
 * Never accepts client-supplied snapshots as truth.
 */
public final class BuildingEditRecoveryCoordinator {
    public record ItemResult(UUID transactionId, UUID buildingId, boolean ok, String message) {}

    public record BatchResult(List<ItemResult> items) {
        public BatchResult {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public int recovered() {
            int n = 0;
            for (ItemResult i : items) if (i.ok()) n++;
            return n;
        }

        public int blocked() {
            return items.size() - recovered();
        }
    }

    private BuildingEditRecoveryCoordinator() {}

    /**
     * Scan durable open transactions and attempt recovery against live worlds.
     * Safe to call on server start (after Memory + txn store bind) and on demand.
     */
    public static BatchResult recoverOpenTransactions(MinecraftServer server, MemoryManager memoryManager) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(memoryManager, "memoryManager");
        BuildingEditService service = new BuildingEditService(memoryManager);
        List<ItemResult> out = new ArrayList<>();
        for (BuildingEditTransaction txn : BuildingEditTransactionStore.listOpen()) {
            out.add(recoverOne(server, service, memoryManager, txn));
        }
        BatchResult batch = new BatchResult(out);
        if (!out.isEmpty()) {
            FormacraftMod.LOGGER.info(
                    "[BuildingEditRecovery] scanned={} recovered={} still_open={}",
                    out.size(), batch.recovered(), batch.blocked());
        }
        return batch;
    }

    public static ItemResult recoverOne(
            MinecraftServer server,
            BuildingEditService service,
            MemoryManager memoryManager,
            BuildingEditTransaction txn
    ) {
        if (txn == null) return new ItemResult(null, null, false, "txn_missing");
        UUID txnId = txn.transactionId();
        UUID buildingId = txn.buildingId();
        String invalid = validateTxn(txn);
        if (invalid != null) {
            return new ItemResult(txnId, buildingId, false, invalid);
        }

        ServerWorld world = resolveWorld(server, txn.dimension());
        if (world == null) {
            return new ItemResult(txnId, buildingId, false, "dimension_unavailable:" + txn.dimension());
        }
        Map<BlockPos, String> live = BuildingEditService.captureLiveStates(world, txn);
        return recoverWithLiveSnapshot(service, memoryManager, txn, live);
    }

    /**
     * Core recovery once a server-authoritative live snapshot is available.
     * Exposed for fault-injection tests without a live {@link ServerWorld}.
     */
    public static ItemResult recoverWithLiveSnapshot(
            BuildingEditService service,
            MemoryManager memoryManager,
            BuildingEditTransaction txn,
            Map<BlockPos, String> live
    ) {
        if (txn == null) return new ItemResult(null, null, false, "txn_missing");
        UUID txnId = txn.transactionId();
        UUID buildingId = txn.buildingId();
        String invalid = validateTxn(txn);
        if (invalid != null) return new ItemResult(txnId, buildingId, false, invalid);
        if (live == null) return new ItemResult(txnId, buildingId, false, "live_world_states_required");

        if (txn.status() == BuildingEditTransaction.Status.PREPARED) {
            if (BuildingEditService.statesEqualPublic(live, txn.expectedWorldStates())) {
                if (!service.abortTransaction(txnId)) {
                    return new ItemResult(txnId, buildingId, false, "txn_abort_persist_failed");
                }
                return new ItemResult(txnId, buildingId, true, "prepared_aborted_world_unchanged");
            }
            service.markRecoveryRequired(txnId);
            return new ItemResult(txnId, buildingId, false, "prepared_world_drift");
        }

        boolean worldAtTarget = BuildingEditService.statesEqualPublic(live, txn.targetWorldStates());
        boolean worldAtExpected = BuildingEditService.statesEqualPublic(live, txn.expectedWorldStates());
        boolean memoryCommitted = memoryLooksCommitted(memoryManager, txn);

        if (worldAtTarget) {
            // World proves the edit; Memory may still need commit or only txn finalize.
            BuildingEditService.RepairResult repaired = service.repairMemorySyncWithLiveWorld(
                    buildingId, txn.dimension(), live);
            return new ItemResult(txnId, buildingId, repaired.ok(),
                    repaired.ok()
                            ? (memoryCommitted ? "finalized_memory_already_committed" : "repaired")
                            : repaired.message());
        }

        if (worldAtExpected) {
            if (memoryCommitted) {
                // Memory advanced but world was undone — do not silently close or invent a patch.
                service.markRecoveryRequired(txnId);
                return new ItemResult(txnId, buildingId, false, "memory_ahead_world_undone_keep_open");
            }
            BuildingEditService.RepairResult repaired = service.repairMemorySyncWithLiveWorld(
                    buildingId, txn.dimension(), live);
            return new ItemResult(txnId, buildingId, repaired.ok(),
                    repaired.ok() ? "repaired_world_undone" : repaired.message());
        }

        // Third state: player (or other) changed cells after the edit attempt.
        service.markRecoveryRequired(txnId);
        if (memoryCommitted) {
            return new ItemResult(txnId, buildingId, false, "committed_memory_world_diverged_keep_open");
        }
        return new ItemResult(txnId, buildingId, false, "world_state_mismatch_keep_recovery_required");
    }

    static boolean memoryLooksCommitted(MemoryManager memoryManager, BuildingEditTransaction txn) {
        if (memoryManager == null || txn == null) return false;
        ProjectMemory memory = memoryManager.getMemory(txn.buildingId().toString());
        if (memory == null) return false;
        long current = BuildingMemoryKeys.revisionOf(memory);
        if (current != txn.baseRevision() + 1L) return false;
        BuildingPartIndex index = BuildingPartIndex.fromMemory(memory);
        return BuildingEditService.roofStatesMatchPublic(index.roof().recordedStates(), txn.targetWorldStates());
    }

    private static String validateTxn(BuildingEditTransaction txn) {
        if (txn.transactionId() == null) return "txn_corrupt:transactionId";
        if (txn.buildingId() == null) return "txn_corrupt:buildingId";
        if (txn.dimension() == null) return "txn_corrupt:dimension";
        if (txn.operation() == null) return "txn_corrupt:operation";
        if (txn.expectedWorldStates() == null || txn.expectedWorldStates().isEmpty()) {
            return "txn_corrupt:expectedWorldStates";
        }
        if (txn.targetWorldStates() == null || txn.targetWorldStates().isEmpty()) {
            return "txn_corrupt:targetWorldStates";
        }
        if (txn.baseRevision() < 0L) return "txn_corrupt:baseRevision";
        return null;
    }

    private static ServerWorld resolveWorld(MinecraftServer server, Identifier dimension) {
        if (server == null || dimension == null) return null;
        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, dimension);
        return server.getWorld(key);
    }
}
