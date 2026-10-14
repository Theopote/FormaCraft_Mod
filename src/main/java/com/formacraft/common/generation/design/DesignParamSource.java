package com.formacraft.common.generation.design;

/** Provenance for architectural parameters on a canonical plan. */
public enum DesignParamSource {
    USER_EXPLICIT,
    AI_PLANNED,
    DERIVED_GEOMETRY,
    TECHNICAL_DEFAULT,
    /** Transition tag for Java-inferred satellites; should become replan, not silent infer. */
    COMPILER_INFERRED;

    public static DesignParamSource parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public boolean isDesignLocked() {
        return this == USER_EXPLICIT || this == AI_PLANNED;
    }
}
