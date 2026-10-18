package com.formacraft.server.preview;

import net.minecraft.util.math.BlockPos;

import java.util.Objects;
import java.util.UUID;

/**
 * Explicit generation-request ownership for Preview → Confirm binding.
 * <p>
 * {@code requestId} — one generate request (unique)<br>
 * {@code playerId} — owner (required when known)<br>
 * {@code worldKey}/{@code origin} — placement identity
 */
public final class GenerationRequestContext {

    public record Scope(
            UUID requestId,
            UUID playerId,
            String worldKey,
            BlockPos origin
    ) {
        public Scope {
            Objects.requireNonNull(requestId, "requestId");
            worldKey = worldKey == null ? "unknown" : worldKey;
            origin = origin == null ? BlockPos.ORIGIN : origin.toImmutable();
        }
    }

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private GenerationRequestContext() {}

    /** Start a scoped request owned by {@code playerId}. */
    public static Scope begin(UUID playerId, String worldKey, BlockPos origin) {
        Scope scope = new Scope(UUID.randomUUID(), playerId, worldKey, origin);
        CURRENT.set(scope);
        return scope;
    }

    /** Compatibility: request id only (prefer {@link #begin(UUID, String, BlockPos)}). */
    public static UUID begin() {
        Scope existing = CURRENT.get();
        if (existing != null) return existing.requestId();
        Scope scope = new Scope(UUID.randomUUID(), null, "unknown", BlockPos.ORIGIN);
        CURRENT.set(scope);
        return scope.requestId();
    }

    public static Scope current() {
        return CURRENT.get();
    }

    public static UUID currentRequestId() {
        Scope s = CURRENT.get();
        return s == null ? null : s.requestId();
    }

    /**
     * Ensure a scope exists. If missing, creates one with the provided ownership.
     * Does not override an existing scope's player/world/origin.
     */
    public static Scope currentOrCreate(UUID playerId, String worldKey, BlockPos origin) {
        Scope s = CURRENT.get();
        if (s != null) return s;
        return begin(playerId, worldKey, origin);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** @deprecated use {@link #currentRequestId()} / {@link #current()} */
    @Deprecated
    public static UUID currentOrCreate() {
        Scope s = CURRENT.get();
        if (s != null) return s.requestId();
        return begin();
    }
}
