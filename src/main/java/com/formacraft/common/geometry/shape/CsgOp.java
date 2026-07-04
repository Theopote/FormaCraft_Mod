package com.formacraft.common.geometry.shape;

import java.util.Locale;

public enum CsgOp {
    UNION,
    SUBTRACT,
    INTERSECT;

    public static CsgOp parse(Object raw) {
        if (raw == null) {
            return UNION;
        }
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "subtract", "sub", "difference", "minus", "carve", "cut" -> SUBTRACT;
            case "intersect", "intersection", "and" -> INTERSECT;
            default -> UNION;
        };
    }
}
