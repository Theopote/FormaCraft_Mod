package com.formacraft.server.generation.component.adaptor;

import java.util.List;

/** Documented component generation route (Phase 4 authority map). */
public record ComponentRouteDecision(
        String executorId,
        String reason,
        List<String> forbiddenFallbacks
) {
    public static ComponentRouteDecision of(String executorId, String reason) {
        return new ComponentRouteDecision(executorId, reason, List.of());
    }
}
