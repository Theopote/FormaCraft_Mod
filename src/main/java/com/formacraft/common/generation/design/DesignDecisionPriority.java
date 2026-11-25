package com.formacraft.common.generation.design;

/**
 * Canonical design-decision priority for the LlmPlan compile trunk.
 * <p>
 * Higher priority (lower ordinal) wins. Style / aesthetic defaults may only
 * fill gaps and must never overwrite explicit {@code none}/{@code off}/{@code false}.
 * When {@link #USER_EXPLICIT} conflicts with a {@link #HARD_CONSTRAINT}, compilers
 * must emit a structured diagnostic / {@code capability_gap} — never silent success
 * after redesign.
 * <p>
 * Classification: HARD_CONSTRAINT process rule (documents authority; does not invent style).
 */
public enum DesignDecisionPriority {
    /** World safety, spatial integrity, BlockState legality, resource caps. */
    HARD_CONSTRAINT,
    /** Player-stated design (sealed or USER_EXPLICIT provenance). */
    USER_EXPLICIT,
    /** Model-planned design (AI_PLANNED provenance). */
    AI_PLANNED,
    /** Deterministic geometry realizing an authorized design. */
    GEOMETRY_EXECUTION,
    /** Style / aesthetic post-pass TECHNICAL_DEFAULT fill. */
    STYLE_DEFAULT;

    /** True when this level outranks {@code other} (strictly higher priority). */
    public boolean outranks(DesignDecisionPriority other) {
        return other != null && this.ordinal() < other.ordinal();
    }

    /**
     * Map a {@link DesignParamSource} onto the design half of this priority ladder.
     * Hard constraints and geometry execution are not provenance tags — callers
     * classify those at the call site.
     */
    public static DesignDecisionPriority fromSource(DesignParamSource source) {
        if (source == null) {
            return STYLE_DEFAULT;
        }
        return switch (source) {
            case USER_EXPLICIT -> USER_EXPLICIT;
            case AI_PLANNED -> AI_PLANNED;
            case DERIVED_GEOMETRY, COMPILER_INFERRED -> GEOMETRY_EXECUTION;
            case TECHNICAL_DEFAULT -> STYLE_DEFAULT;
        };
    }

    /**
     * Aesthetic param classification for relief / trim / weathering / lighting / density.
     * Classification: HARD_CONSTRAINT process rule — does not invent style values.
     */
    public enum AestheticParamClass {
        /** Key missing / null — {@link #styleDefaultMayFill} may apply STYLE_DEFAULT. */
        ABSENT,
        /** Non-empty value that parses to a concrete enum. */
        VALID,
        /** Explicit disable ({@code false}/{@code 0}/{@code none}/{@code off}) — map to NONE/FLAT. */
        DISABLED,
        /** Non-empty value that does not parse; never silent STYLE_DEFAULT. */
        INVALID
    }

    /**
     * Classify a raw aesthetic param before parse / style-default fill (Phase 01.9 Task C).
     */
    public static AestheticParamClass classifyAestheticParam(Object existingValue) {
        if (existingValue == null) {
            return AestheticParamClass.ABSENT;
        }
        if (existingValue instanceof Boolean b) {
            return b ? AestheticParamClass.VALID : AestheticParamClass.DISABLED;
        }
        if (existingValue instanceof Number n) {
            if (n.doubleValue() == 0.0) {
                return AestheticParamClass.DISABLED;
            }
            // Positive numbers alone are not aesthetic enum tokens.
            return AestheticParamClass.INVALID;
        }
        String s = String.valueOf(existingValue).trim();
        if (s.isEmpty()) {
            // Empty string is an explicit clear / disable marker, not ABSENT.
            return AestheticParamClass.DISABLED;
        }
        String lower = s.toLowerCase(java.util.Locale.ROOT);
        if ("none".equals(lower) || "off".equals(lower) || "false".equals(lower) || "0".equals(lower)) {
            return AestheticParamClass.DISABLED;
        }
        // VALID vs INVALID is decided by the field parser; non-disable tokens are parse candidates.
        return AestheticParamClass.VALID;
    }

    /**
     * Aesthetic / style defaults may fill only when the value is {@link AestheticParamClass#ABSENT}.
     * Explicit disable markers ({@code none}/{@code off}/{@code false}/{@code 0}/empty) and
     * invalid non-empty tokens must not be overwritten by STYLE_DEFAULT.
     */
    public static boolean styleDefaultMayFill(Object existingValue) {
        return classifyAestheticParam(existingValue) == AestheticParamClass.ABSENT;
    }

    /**
     * True when a locked design source conflicts with an immovable hard constraint.
     * Callers must surface {@code capability_gap} / structured diagnostic — never rewrite.
     */
    public static boolean requiresCapabilityGap(
            DesignParamSource designSource,
            boolean hardConstraintBlocksExecution
    ) {
        if (!hardConstraintBlocksExecution || designSource == null) {
            return false;
        }
        return designSource == DesignParamSource.USER_EXPLICIT
                || designSource == DesignParamSource.AI_PLANNED;
    }
}
