package com.formacraft.server.preview;

import java.util.UUID;

/**
 * Per-thread generation request identity for Preview → Confirm binding.
 * Isolates concurrent players / parallel previews — never use patchHash alone.
 */
public final class GenerationRequestIds {
    private GenerationRequestIds() {}

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    /** Start a new request scope (call at preview-build entry). */
    public static UUID begin() {
        UUID id = UUID.randomUUID();
        CURRENT.set(id);
        return id;
    }

    /** Current scope id, or null if none. */
    public static UUID current() {
        return CURRENT.get();
    }

    /** Current scope, or create one if missing (interpret path). */
    public static UUID currentOrCreate() {
        UUID id = CURRENT.get();
        if (id == null) {
            id = UUID.randomUUID();
            CURRENT.set(id);
        }
        return id;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
