package com.formacraft.common.io;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Crash-safe text file replacement and CREATE_NEW publish moves.
 * Prefer {@link #writeString} over {@link Files#writeString(Path, CharSequence)} when overwriting durable JSON.
 * Prefer {@link #moveCreateNew} for staging→destination directory/file publishes that must not replace.
 */
public final class AtomicFiles {
    private AtomicFiles() {}

    public static void writeString(Path path, String content) throws IOException {
        writeString(path, content, StandardCharsets.UTF_8, ".fc-");
    }

    public static void writeString(Path path, String content, Charset charset, String tempPrefix)
            throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path is required");
        }
        if (content == null) {
            throw new IllegalArgumentException("content is required");
        }
        Path temporary = createSiblingTemp(path, tempPrefix);
        try {
            Files.writeString(temporary, content, charset == null ? StandardCharsets.UTF_8 : charset);
            moveReplaceExisting(temporary, path);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Crash-safe binary replacement via sibling temp + {@link #moveReplaceExisting}. */
    public static void writeBytes(Path path, byte[] bytes) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path is required");
        }
        if (bytes == null) {
            throw new IllegalArgumentException("bytes are required");
        }
        Path temporary = createSiblingTemp(path, ".fc-");
        try {
            Files.write(temporary, bytes);
            moveReplaceExisting(temporary, path);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path createSiblingTemp(Path path, String tempPrefix) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path dir = parent != null ? parent : Path.of(".");
        String prefix = (tempPrefix == null || tempPrefix.isBlank()) ? ".fc-" : tempPrefix;
        return Files.createTempFile(dir, prefix, ".tmp");
    }

    /**
     * Publish {@code source} to a non-existing {@code target}. Prefers {@code ATOMIC_MOVE};
     * falls back when the filesystem rejects atomic moves (Docker overlayfs, some network mounts).
     * Brief {@link AccessDeniedException} retries cover Windows AV scanners locking the new path.
     */
    public static void moveCreateNew(Path source, Path target) throws IOException {
        move(source, target, false);
    }

    /** Same fallback/retry policy as {@link #moveCreateNew}, but allows replacing {@code target}. */
    public static void moveReplaceExisting(Path source, Path target) throws IOException {
        move(source, target, true);
    }

    private static void move(Path source, Path target, boolean replace) throws IOException {
        if (source == null || target == null) {
            throw new IllegalArgumentException("source and target are required");
        }
        AccessDeniedException lastDenied = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                try {
                    if (replace) {
                        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                    }
                } catch (AtomicMoveNotSupportedException unsupported) {
                    if (replace) {
                        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        Files.move(source, target);
                    }
                }
                return;
            } catch (AccessDeniedException denied) {
                lastDenied = denied;
                try {
                    Thread.sleep(15L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw denied;
                }
            }
        }
        throw lastDenied;
    }
}
