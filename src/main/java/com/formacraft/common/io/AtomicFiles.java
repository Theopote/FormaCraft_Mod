package com.formacraft.common.io;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Crash-safe text file replacement: write a sibling temp file, then {@code move} into place.
 * Prefer this over {@link Files#writeString(Path, CharSequence)} when overwriting durable JSON.
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
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path dir = parent != null ? parent : Path.of(".");
        String prefix = (tempPrefix == null || tempPrefix.isBlank()) ? ".fc-" : tempPrefix;
        Path temporary = Files.createTempFile(dir, prefix, ".tmp");
        try {
            Files.writeString(temporary, content, charset == null ? StandardCharsets.UTF_8 : charset);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
