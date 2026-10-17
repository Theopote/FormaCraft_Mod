package com.formacraft.common.generation.design;

/**
 * How {@code design_requirements.circulation=REQUIRED} proves walkability.
 * Read from {@code plan_meta.circulation_scope}; default {@link #BUILDING_LOCAL}.
 */
public enum CirculationScope {
    /** Each mass: own ground → own upper storeys. Independent buildings OK. */
    BUILDING_LOCAL,
    /** All circulation masses share one walkable network (grounds mutually reachable). */
    CONNECTED_COMPLEX;

    public static final String META_KEY = "circulation_scope";

    public static CirculationScope parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
