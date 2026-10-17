package com.formacraft.common.generation.design;

/**
 * How attached {@code host_id} edges are proven when circulation is REQUIRED.
 * Read from {@code plan_meta.circulation_host_links}; default {@link #ROOT}.
 */
public enum CirculationHostLinks {
    /** Design ownership + reachability from the independent root entry (Round-26). */
    ROOT,
    /** Each direct parent→child edge must also be walkable (Round-27). */
    DIRECT;

    public static final String META_KEY = "circulation_host_links";

    public static CirculationHostLinks parse(String raw) {
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
