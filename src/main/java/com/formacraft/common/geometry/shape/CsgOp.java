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
            case "add", "union" -> UNION;
            case "subtract", "sub", "difference", "minus", "carve", "cut" -> SUBTRACT;
            case "intersect", "intersection", "and" -> INTERSECT;
            default -> throw new IllegalArgumentException("E_PRIMITIVE_CSG_OP_UNSUPPORTED: " + s);
        };
    }
}
