package com.formacraft.server.edit;

import com.formacraft.FormacraftMod;
import com.formacraft.common.json.JsonUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable store for building-edit transactions (independent of ProjectMemory JSON).
 * World writes must not proceed unless {@link #putPrepared} succeeds.
 */
public final class BuildingEditTransactionStore {
    private static final Map<UUID, BuildingEditTransaction> BY_ID = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> OPEN_BY_BUILDING = new ConcurrentHashMap<>();
    private static volatile Path persistRoot;

    private BuildingEditTransactionStore() {}

    /** Bind to world save directory; loads unfinished transactions for restart recovery. */
    public static void bindServer(MinecraftServer server) {
        if (server == null) {
            persistRoot = null;
            return;
        }
        persistRoot = server.getSavePath(WorldSavePath.ROOT).resolve("formacraft").resolve("edit_txns");
        loadAllFromDisk();
    }

    public static void clearForTest() {
        BY_ID.clear();
        OPEN_BY_BUILDING.clear();
        persistRoot = null;
        FAIL_NEXT_PERSIST.set(false);
    }

    /** Test-only: set an explicit directory for durable txn files. */
    public static void bindDirectoryForTest(Path root) {
        persistRoot = root;
        BY_ID.clear();
        OPEN_BY_BUILDING.clear();
        if (root != null) loadAllFromDisk();
    }

    public static BuildingEditTransaction get(UUID transactionId) {
        return transactionId == null ? null : BY_ID.get(transactionId);
    }

    public static BuildingEditTransaction findOpenByBuilding(UUID buildingId) {
        if (buildingId == null) return null;
        UUID txnId = OPEN_BY_BUILDING.get(buildingId);
        if (txnId == null) return null;
        BuildingEditTransaction txn = BY_ID.get(txnId);
        if (txn == null || !txn.isOpen()) {
            OPEN_BY_BUILDING.remove(buildingId, txnId);
            return null;
        }
        return txn;
    }

    public static List<BuildingEditTransaction> listOpen() {
        List<BuildingEditTransaction> out = new ArrayList<>();
        for (BuildingEditTransaction txn : BY_ID.values()) {
            if (txn != null && txn.isOpen()) out.add(txn);
        }
        return List.copyOf(out);
    }

    /**
     * Persist PREPARED transaction before world write. Returns false if durable save fails
     * (when a persist root is configured) or if another open txn already owns the building.
     */
    public static boolean putPrepared(BuildingEditTransaction txn) {
        if (txn == null) return false;
        BuildingEditTransaction existing = findOpenByBuilding(txn.buildingId());
        if (existing != null && !existing.transactionId().equals(txn.transactionId())) {
            return false;
        }
        if (!persist(txn)) return false;
        BY_ID.put(txn.transactionId(), txn);
        OPEN_BY_BUILDING.put(txn.buildingId(), txn.transactionId());
        return true;
    }

    public static boolean updateStatus(UUID transactionId, BuildingEditTransaction.Status status) {
        BuildingEditTransaction txn = get(transactionId);
        if (txn == null || status == null) return false;
        BuildingEditTransaction.Status previous = txn.status();
        txn.setStatus(status);
        if (!persist(txn)) {
            FormacraftMod.LOGGER.error("Failed to persist building-edit txn {} status={}",
                    transactionId, status);
            // Durable-intent open statuses may stay in-memory so this session keeps blocking.
            // Closing statuses (MEMORY_COMMITTED / ABORTED) must not succeed without disk —
            // otherwise restart reloads an open txn while in-memory already unlocked the building.
            if (status == BuildingEditTransaction.Status.RECOVERY_REQUIRED
                    || status == BuildingEditTransaction.Status.WORLD_APPLIED) {
                OPEN_BY_BUILDING.put(txn.buildingId(), txn.transactionId());
                return true;
            }
            txn.setStatus(previous);
            if (txn.isOpen()) {
                OPEN_BY_BUILDING.put(txn.buildingId(), txn.transactionId());
            } else {
                OPEN_BY_BUILDING.remove(txn.buildingId(), txn.transactionId());
            }
            return false;
        }
        if (txn.isOpen()) {
            OPEN_BY_BUILDING.put(txn.buildingId(), txn.transactionId());
        } else {
            OPEN_BY_BUILDING.remove(txn.buildingId(), txn.transactionId());
        }
        return true;
    }

    /**
     * Test/fault-injection: force the next {@link #persist} call to fail once when a root is bound.
     * No-op when running in-memory-only (persistRoot == null).
     */
    static final ThreadLocal<Boolean> FAIL_NEXT_PERSIST = ThreadLocal.withInitial(() -> false);

    public static void failNextPersistForTest() {
        FAIL_NEXT_PERSIST.set(true);
    }

    private static boolean persist(BuildingEditTransaction txn) {
        Path root = persistRoot;
        if (root == null) return true; // in-memory only (unit tests / no server)
        if (Boolean.TRUE.equals(FAIL_NEXT_PERSIST.get())) {
            FAIL_NEXT_PERSIST.set(false);
            return false;
        }
        try {
            Files.createDirectories(root);
            Path path = root.resolve("txn_" + txn.transactionId() + ".json");
            String json = JsonUtil.toJson(txn.toMap());
            Path temporary = Files.createTempFile(root, ".txn-", ".tmp");
            try {
                Files.writeString(temporary, json, StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, path,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                    Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            return true;
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("Failed to persist building-edit transaction {}", txn.transactionId(), e);
            return false;
        }
    }

    private static void loadAllFromDisk() {
        Path root = persistRoot;
        if (root == null || !Files.isDirectory(root)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "txn_*.json")) {
            for (Path path : stream) {
                try {
                    String json = Files.readString(path, StandardCharsets.UTF_8);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = JsonUtil.fromJson(json, Map.class);
                    BuildingEditTransaction txn = BuildingEditTransaction.fromMap(map);
                    if (txn == null) continue;
                    BY_ID.put(txn.transactionId(), txn);
                    if (txn.isOpen()) {
                        OPEN_BY_BUILDING.put(txn.buildingId(), txn.transactionId());
                    }
                } catch (Exception e) {
                    FormacraftMod.LOGGER.warn("Skipping corrupt edit txn file {}: {}", path, e.toString());
                }
            }
        } catch (IOException e) {
            FormacraftMod.LOGGER.error("Failed to load building-edit transactions", e);
        }
    }

    public static int sizeForTest() {
        return BY_ID.size();
    }

    public static void dropClosedForTest() {
        Iterator<Map.Entry<UUID, BuildingEditTransaction>> it = BY_ID.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, BuildingEditTransaction> e = it.next();
            if (e.getValue() == null || !e.getValue().isOpen()) {
                it.remove();
            }
        }
    }
}
