package com.formacraft.common.generation.component.util;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Canonical {@code plate_role} tokens accepted when explicitly planned. */
public final class PlateRoleCatalog {
    private PlateRoleCatalog() {}

    public static final String[] ROLE_KEYS = {"plate_role", "plateRole"};

    public static final Set<String> INTERIOR_TOKENS = Set.of(
            "interior", "floor", "slab", "internal"
    );

    public static final Set<String> TERRACE_TOKENS = Set.of(
            "terrace", "balcony", "deck", "cantilever"
    );

    public static final Set<String> BRIDGE_TOKENS = Set.of(
            "bridge", "link", "connector"
    );

    public static final Set<String> SUPPORTED_TOKENS;

    static {
        java.util.HashSet<String> all = new java.util.HashSet<>();
        all.addAll(INTERIOR_TOKENS);
        all.addAll(TERRACE_TOKENS);
        all.addAll(BRIDGE_TOKENS);
        SUPPORTED_TOKENS = Set.copyOf(all);
    }

    public static Optional<String> extractExplicit(Map<String, Object> params) {
        if (params == null) {
            return Optional.empty();
        }
        for (String key : ROLE_KEYS) {
            if (!params.containsKey(key) || params.get(key) == null) {
                continue;
            }
            String value = String.valueOf(params.get(key)).trim();
            if (!value.isEmpty()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    public static boolean isSupportedExplicit(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        return SUPPORTED_TOKENS.contains(raw.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Map a supported token to a plate role. Unsupported tokens return empty
     * (callers must not silently map to INTERIOR).
     */
    public static Optional<HostedPlateOccupancy.PlateRole> parseSupported(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.of(HostedPlateOccupancy.PlateRole.INTERIOR);
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_TOKENS.contains(v)) {
            return Optional.empty();
        }
        if (TERRACE_TOKENS.contains(v)) {
            return Optional.of(HostedPlateOccupancy.PlateRole.TERRACE);
        }
        if (BRIDGE_TOKENS.contains(v)) {
            return Optional.of(HostedPlateOccupancy.PlateRole.BRIDGE);
        }
        return Optional.of(HostedPlateOccupancy.PlateRole.INTERIOR);
    }
}
