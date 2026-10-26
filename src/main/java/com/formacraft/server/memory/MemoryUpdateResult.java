package com.formacraft.server.memory;

/**
 * Explicit success/failure for Memory persistence used by building-edit transactions.
 * Soft {@link MemoryManager#updateMemory} must not be treated as durable commit.
 */
public record MemoryUpdateResult(boolean success, String message) {
    public boolean ok() {
        return success;
    }

    public static MemoryUpdateResult succeeded() {
        return new MemoryUpdateResult(true, null);
    }

    public static MemoryUpdateResult fail(String message) {
        return new MemoryUpdateResult(false, message == null ? "memory_update_failed" : message);
    }
}
