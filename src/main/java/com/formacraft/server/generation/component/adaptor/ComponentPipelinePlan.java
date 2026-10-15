package com.formacraft.server.generation.component.adaptor;

import java.util.List;

/** Resolved pipeline order for one component (no geometry generated). */
public record ComponentPipelinePlan(
        boolean explicitLibraryFirst,
        boolean skipTypologyWhenLibraryCommits,
        List<ComponentPipelineStep> steps
) {
    public static ComponentPipelinePlan empty() {
        return new ComponentPipelinePlan(false, false, List.of());
    }

    public boolean isStepEligible(ComponentPipelineStepId id) {
        if (steps == null) {
            return false;
        }
        for (ComponentPipelineStep step : steps) {
            if (step.id() == id) {
                return step.eligible();
            }
        }
        return false;
    }
}
