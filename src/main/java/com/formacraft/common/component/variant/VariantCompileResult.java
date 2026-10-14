package com.formacraft.common.component.variant;

import com.formacraft.common.patch.BlockPatch;

import java.util.List;

/** Structured outcome of {@link ComponentVariantCompiler} so callers do not treat all empty lists as success. */
public record VariantCompileResult(Status status, List<BlockPatch> patches, String message) {
    public enum Status {
        SUCCESS,
        EMPTY_ALLOWED,
        INVALID_INPUT,
        TEMPLATE_MISSING,
        GEOMETRY_INVALID
    }

    public VariantCompileResult {
        patches = patches == null ? List.of() : List.copyOf(patches);
        message = message == null ? "" : message;
    }

    public static VariantCompileResult success(List<BlockPatch> patches) {
        if (patches == null || patches.isEmpty()) {
            return new VariantCompileResult(Status.EMPTY_ALLOWED, List.of(), "empty_geometry");
        }
        return new VariantCompileResult(Status.SUCCESS, patches, "");
    }

    public static VariantCompileResult failure(Status status, String message) {
        return new VariantCompileResult(status, List.of(), message);
    }

    public boolean ok() {
        return status == Status.SUCCESS || status == Status.EMPTY_ALLOWED;
    }
}
