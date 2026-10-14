package com.formacraft.common.component.query;

import com.formacraft.common.component.ComponentDefinition;

/**
 * Structured library lookup: distinguishes no hit from adapt failure so callers
 * never silently substitute procedural geometry for a known-but-unfittable capture.
 */
public record ComponentResolveResult(
        Status status,
        ComponentDefinition definition,
        String sourceId,
        String message
) {
    public enum Status {
        SUCCESS,
        NO_CANDIDATE,
        ADAPT_FAILED,
        LOAD_FAILED
    }

    public ComponentResolveResult {
        message = message == null ? "" : message;
        sourceId = sourceId == null ? "" : sourceId;
    }

    public static ComponentResolveResult success(ComponentDefinition definition) {
        String id = definition != null && definition.id != null ? definition.id : "";
        return new ComponentResolveResult(Status.SUCCESS, definition, id, "");
    }

    public static ComponentResolveResult noCandidate(String message) {
        return new ComponentResolveResult(Status.NO_CANDIDATE, null, "", message);
    }

    public static ComponentResolveResult adaptFailed(String sourceId, String message) {
        return new ComponentResolveResult(Status.ADAPT_FAILED, null, sourceId == null ? "" : sourceId, message);
    }

    public static ComponentResolveResult loadFailed(String sourceId, String message) {
        return new ComponentResolveResult(Status.LOAD_FAILED, null, sourceId == null ? "" : sourceId, message);
    }

    public boolean ok() {
        return status == Status.SUCCESS && definition != null
                && definition.blocks != null && !definition.blocks.isEmpty();
    }
}
