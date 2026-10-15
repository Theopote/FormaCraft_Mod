package com.formacraft.server.generation.component.adaptor;

/** Hint-only pipeline stage; {@code eligible} does not imply patches will be non-empty. */
public record ComponentPipelineStep(ComponentPipelineStepId id, boolean eligible, String note) {
    public static ComponentPipelineStep of(ComponentPipelineStepId id, boolean eligible) {
        return new ComponentPipelineStep(id, eligible, null);
    }
}
