package com.formacraft.common.network;

import java.util.Objects;
import java.util.UUID;

/** An authenticated server task reference, never a client-provided receipt or path. */
public record CommunitySelectionRef(UUID sessionId,UUID taskId) {
    public CommunitySelectionRef { Objects.requireNonNull(sessionId); Objects.requireNonNull(taskId); }
}
