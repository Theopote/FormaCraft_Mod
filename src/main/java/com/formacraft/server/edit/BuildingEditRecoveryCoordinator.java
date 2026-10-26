package com.formacraft.server.edit;

import com.formacraft.FormacraftMod;
import com.formacraft.server.memory.MemoryManager;
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
 * Reads live {@link ServerWorld} cells — never accepts client-supplied snapshots as truth.
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
            out.add(recoverOne(server, service, txn));
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
            BuildingEditTransaction txn
    ) {
        if (txn == null) return new ItemResult(null, null, false, "txn_missing");
        UUID txnId = txn.transactionId();
        UUID buildingId = txn.buildingId();
        // Prefer finalize when Memory already committed (status persist gap).
        BuildingEditService.RepairResult finalize = service.finalizeCommittedTransaction(txnId);
        if (finalize.ok()) {
            return new ItemResult(txnId, buildingId, true, "finalized_memory_already_committed");
        }
        if ("txn_finalize_persist_failed".equals(finalize.message())) {
            return new ItemResult(txnId, buildingId, false, finalize.message());
        }

        ServerWorld world = resolveWorld(server, txn.dimension());
        if (world == null) {
            return new ItemResult(txnId, buildingId, false, "dimension_unavailable:" + txn.dimension());
        }
        // PREPARED with no world write yet: if live still matches expected, abort safely.
        if (txn.status() == BuildingEditTransaction.Status.PREPARED) {
            Map<BlockPos, String> live = BuildingEditService.captureLiveStates(world, txn);
            if (statesMatchAll(live, txn.expectedWorldStates())) {
                if (!service.abortTransaction(txnId)) {
                    return new ItemResult(txnId, buildingId, false, "txn_abort_persist_failed");
                }
                return new ItemResult(txnId, buildingId, true, "prepared_aborted_world_unchanged");
            }
            // Unexpected world drift before apply — keep open for operator review.
            service.markRecoveryRequired(txnId);
            return new ItemResult(txnId, buildingId, false, "prepared_world_drift");
        }

        Map<BlockPos, String> live = BuildingEditService.captureLiveStates(world, txn);
        BuildingEditService.RepairResult repaired = service.repairMemorySyncWithLiveWorld(
                buildingId, txn.dimension(), live);
        return new ItemResult(txnId, buildingId, repaired.ok(),
                repaired.ok() ? "repaired" : repaired.message());
    }

    private static ServerWorld resolveWorld(MinecraftServer server, Identifier dimension) {
        if (server == null || dimension == null) return null;
        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, dimension);
        return server.getWorld(key);
    }

    private static boolean statesMatchAll(Map<BlockPos, String> live, Map<BlockPos, String> expected) {
        if (expected == null || expected.isEmpty()) return false;
        for (var e : expected.entrySet()) {
            String actual = live.get(e.getKey());
            if (!Objects.equals(actual, e.getValue())) {
                // Allow canonical BlockState string equivalence via repair path; PREPARED abort is strict.
                return false;
            }
        }
        return true;
    }
}
