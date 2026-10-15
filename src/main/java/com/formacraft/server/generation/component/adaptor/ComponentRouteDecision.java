package com.formacraft.server.generation.component.adaptor;

import java.util.List;

/** Result of {@link ComponentRouteResolver#resolve} — no geometry generated. */
public record ComponentRouteDecision(
        ComponentExecutorId executor,
        String reason,
        List<String> forbiddenFallbacks,
        ComponentPipelinePlan pipelinePlan
) {
    public ComponentRouteDecision {
        if (pipelinePlan == null) {
            pipelinePlan = ComponentPipelinePlan.empty();
        }
    }

    public static ComponentRouteDecision of(ComponentExecutorId executor, String reason) {
        return new ComponentRouteDecision(executor, reason, List.of(), ComponentPipelinePlan.empty());
    }

    /** Legacy string id for logs/tests. */
    public String executorId() {
        return executor == null ? "none" : executor.name().toLowerCase();
    }
}
