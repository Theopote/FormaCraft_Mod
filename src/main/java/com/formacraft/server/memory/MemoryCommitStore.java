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
 * Durable log of pending building Memory commits.
 * <p>
 * Lives next to the Memory files: {@code saves/<world>/formacraft/memory_commits/}.
 * Each file is one {@link MemoryCommitRecord}; the file name carries the stable
 * memory UUID so a retry always targets the same building identity.
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

    /** Persist (or overwrite) a commit record durably. */
    public static void save(MinecraftServer server, MemoryCommitRecord record) throws IOException {
        if (record == null || record.memoryUuid == null) {
            throw new IllegalArgumentException("commit record requires memoryUuid");
        }
        if (server == null) return;
        Path dir = dirFor(server);
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
        List<MemoryCommitRecord> records = new ArrayList<>();
        if (server == null) return records;
        Path dir = dirFor(server);
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
