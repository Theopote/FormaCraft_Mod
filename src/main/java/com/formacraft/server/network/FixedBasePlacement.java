package com.formacraft.server.network;

import com.formacraft.common.model.request.FormaRequest;
import net.minecraft.util.math.BlockPos;

/** A fixed work plane is an explicit placement choice, never an edit-coordinate offset. */
public final class FixedBasePlacement {
    private FixedBasePlacement() {}
    public static boolean enabled(FormaRequest request) {
        return request != null && request.getFixedBaseY() != null
                && (request.getPromptMode() == null || request.getPromptMode().isBlank()
                || "BUILD".equalsIgnoreCase(request.getPromptMode()));
    }
    public static BlockPos origin(FormaRequest request, BlockPos proposed, int bottom, int top) {
        if (!enabled(request)) return proposed;
        return new BlockPos(proposed.getX(), Math.clamp(request.getFixedBaseY(), bottom, top), proposed.getZ());
    }
}
