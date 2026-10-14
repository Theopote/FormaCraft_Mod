package com.formacraft.common.component;

import com.formacraft.common.patch.BlockPatch;

import java.util.List;

/**
 * Structured result for library/captured component expansion (single or mount).
 * Callers must not treat an empty patch list as success without reading {@link #status()}.
 */
public record ComponentGenerationOutcome(
        Status status,
        List<BlockPatch> patches,
        List<GenerationDiagnostic> diagnostics
) {
    public enum Status {
        SUCCESS,
        NO_CANDIDATE,
        ADAPT_FAILED,
        LOAD_FAILED,
        INCOMPATIBLE_MOUNT,
        CARVE_FAILED,
        TEMPLATE_MISSING,
        GEOMETRY_INVALID,
        EMPTY_REJECTED,
        INVALID_INPUT
    }

    public ComponentGenerationOutcome {
        patches = patches == null ? List.of() : List.copyOf(patches);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        if (status == null) status = Status.INVALID_INPUT;
    }

    public static ComponentGenerationOutcome success(List<BlockPatch> patches) {
        return new ComponentGenerationOutcome(Status.SUCCESS, patches, List.of());
    }

    public static ComponentGenerationOutcome failure(Status status, String code, String message) {
        return new ComponentGenerationOutcome(status, List.of(),
                List.of(new GenerationDiagnostic(code, message)));
    }

    public boolean ok() {
        return status == Status.SUCCESS;
    }
}
