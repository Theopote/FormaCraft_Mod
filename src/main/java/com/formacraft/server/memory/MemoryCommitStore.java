package com.formacraft.server.memory;

import com.formacraft.FormacraftMod;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pending building Memory commit log.
 * <p>
 * Lives next to the Memory files: {@code saves/<world>/formacraft/memory_commits/}.
 * Each file is one {@link MemoryCommitRecord}; the file name carries the stable
 * memory UUID so a retry always targets the same building identity.
 * <p>
 * <b>Durability boundary:</b> {@link #save} uses a temp file plus replace/move so a
 * crash mid-write cannot leave a truncated final JSON. A {@code true} return means
 * the filesystem rename/replace completed — it does <em>not</em> guarantee an
 * explicit {@code fsync} to stable storage. Ordinary server restarts recover from
 * completed writes; sudden power loss on some devices may still lose the last write.
 */
public final class MemoryCommitStore {
    private MemoryCommitStore() {}

    private static Path dirFor(MinecraftServer server) {
        return java.util.Objects.requireNonNull(server, "server")
                .getSavePath(net.minecraft.util.WorldSavePath.ROOT)
                .resolve("formacraft")
                .resolve("memory_commits");
    }

    static Path dirForRoot(Path saveRoot) {
        return java.util.Objects.requireNonNull(saveRoot, "saveRoot")
                .resolve("formacraft")
                .resolve("memory_commits");
    }

    private static String fileName(String memoryUuid) {
        return "commit_" + memoryUuid.replaceAll("[^a-zA-Z0-9-]", "") + ".json";
    }

    /**
     * Persist (or overwrite) a commit record.
     *
     * @return {@code true} only when the replace/move of the record file completed;
     *         {@code false} when {@code server} is null
     */
    public static boolean save(MinecraftServer server, MemoryCommitRecord record) throws IOException {
        if (record == null || record.memoryUuid == null) {
            throw new IllegalArgumentException("commit record requires memoryUuid");
        }
        if (server == null) return false;
        return saveToDir(dirFor(server), record);
    }

    /**
     * Persist under an explicit world-save root (tests / tooling).
     *
     * @return {@code true} when the replace/move completed
     */
    public static boolean saveToRoot(Path saveRoot, MemoryCommitRecord record) throws IOException {
        if (record == null || record.memoryUuid == null) {
            throw new IllegalArgumentException("commit record requires memoryUuid");
        }
        if (saveRoot == null) return false;
        return saveToDir(dirForRoot(saveRoot), record);
    }

    private static boolean saveToDir(Path dir, MemoryCommitRecord record) throws IOException {
        Files.createDirectories(dir);
        Path path = dir.resolve(fileName(record.memoryUuid));
        Path tmp = Files.createTempFile(dir, ".commit-", ".tmp");
        try {
            Files.writeString(tmp, record.toJson(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return true;
    }

    /** Remove a commit record (commit finished or cancelled). */
    public static void delete(MinecraftServer server, String memoryUuid) {
        if (memoryUuid == null || server == null) return;
        try {
            Path path = dirFor(server).resolve(fileName(memoryUuid));
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            FormacraftMod.LOGGER.warn("Failed to delete memory commit record {}: {}", memoryUuid, exception.getMessage());
        }
    }

    /** Load all commit records still on disk (survives restarts). */
    public static List<MemoryCommitRecord> loadAll(MinecraftServer server) {
        if (server == null) return List.of();
        return loadAllFromDir(dirFor(server));
    }

    /** Load all commit records under an explicit world-save root (tests / tooling). */
    public static List<MemoryCommitRecord> loadAllFromRoot(Path saveRoot) {
        if (saveRoot == null) return List.of();
        return loadAllFromDir(dirForRoot(saveRoot));
    }

    private static List<MemoryCommitRecord> loadAllFromDir(Path dir) {
        List<MemoryCommitRecord> records = new ArrayList<>();
        if (!Files.exists(dir)) return records;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "commit_*.json")) {
            for (Path path : stream) {
                try {
                    MemoryCommitRecord record = MemoryCommitRecord.fromJson(Files.readString(path, StandardCharsets.UTF_8));
                    if (record != null) records.add(record);
                } catch (Exception exception) {
                    FormacraftMod.LOGGER.warn("Failed to read memory commit record {}: {}",
                            path.getFileName(), exception.getMessage());
                }
            }
        } catch (IOException exception) {
            FormacraftMod.LOGGER.warn("Failed to list memory commit records: {}", exception.getMessage());
        }
        return records;
    }
}
