package com.formacraft.common.component;

import com.formacraft.common.component.variant.VariantCompileResult;
import com.formacraft.common.patch.BlockPatch;

import java.util.List;

/**
 * Final PLACE geometry for library/captured components. Preview and expander must share this
 * artifact rather than re-running transform/material logic independently.
 */
public record ResolvedComponentGeometry(
        VariantCompileResult.Status status,
        List<BlockPatch> patches,
        String sourceComponentId,
        String message
) {
    public ResolvedComponentGeometry {
        patches = patches == null ? List.of() : List.copyOf(patches);
        sourceComponentId = sourceComponentId == null ? "" : sourceComponentId;
        message = message == null ? "" : message;
        if (status == null) status = VariantCompileResult.Status.INVALID_INPUT;
    }

    public static ResolvedComponentGeometry success(String sourceId, List<BlockPatch> patches) {
        if (patches == null || patches.isEmpty()) {
            return new ResolvedComponentGeometry(
                    VariantCompileResult.Status.EMPTY_ALLOWED, List.of(), sourceId, "empty_geometry");
        }
        return new ResolvedComponentGeometry(VariantCompileResult.Status.SUCCESS, patches, sourceId, "");
    }

    public static ResolvedComponentGeometry failure(VariantCompileResult.Status status, String sourceId, String message) {
        return new ResolvedComponentGeometry(status, List.of(), sourceId, message);
    }

    public boolean ok() {
        return status == VariantCompileResult.Status.SUCCESS
                || status == VariantCompileResult.Status.EMPTY_ALLOWED;
    }
}
