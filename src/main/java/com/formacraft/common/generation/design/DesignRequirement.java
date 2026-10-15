package com.formacraft.common.generation.design;

/** Whether a design element must appear in the canonical plan before compile. */
public enum DesignRequirement {
    /** LLM/user required this element; compile fails if absent (when infer disabled). */
    REQUIRED,
    /** May be omitted without failing completeness check. */
    OPTIONAL,
    /** Explicitly not part of the design (pavilion, open roof, etc.). */
    OMITTED,
    /** Not decided — treat as required for completeness when compiler infer is off. */
    UNSPECIFIED;

    public static DesignRequirement parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public boolean mustBePresentWhenStrict() {
        return this == REQUIRED || this == UNSPECIFIED;
    }
}
