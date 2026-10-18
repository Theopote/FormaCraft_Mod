package com.formacraft.server.preview;

import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * Compatibility façade over {@link GenerationRequestContext}.
 * Prefer {@link GenerationRequestContext} for new code.
 */
public final class GenerationRequestIds {
    private GenerationRequestIds() {}

    public static UUID begin() {
        return GenerationRequestContext.begin();
    }

    public static GenerationRequestContext.Scope begin(UUID playerId, String worldKey, BlockPos origin) {
        return GenerationRequestContext.begin(playerId, worldKey, origin);
    }

    public static UUID current() {
        return GenerationRequestContext.currentRequestId();
    }

    public static UUID currentOrCreate() {
        return GenerationRequestContext.currentOrCreate();
    }

    public static void clear() {
        GenerationRequestContext.clear();
    }
}
