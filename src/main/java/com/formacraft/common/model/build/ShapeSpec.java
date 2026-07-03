package com.formacraft.common.model.build;

import java.util.Map;

/**
 * Shape grammar for building footprints.
 */
public record ShapeSpec(
        ShapeType type,
        Map<String, Integer> params,
        int rotation
) {
    public enum ShapeType {
        RECTANGLE,
        L_SHAPE,
        U_SHAPE,
        COURTYARD,
        CIRCLE,
        CROSS
    }
}
