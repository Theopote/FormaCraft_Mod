package com.formacraft.server.semantic;

import java.util.Locale;

/**
 * SemanticZoneType (v1):
 * "social/space role" tags for cluster planning (not architectural style).
 */
public enum SemanticZoneType {
    CORE,
    PUBLIC,
    SEMI_PUBLIC,
    PRIVATE,
    SERVICE,
    LANDSCAPE,
    TRANSITION,
    CIRCULATION;

    public static SemanticZoneType parse(Object v, SemanticZoneType def) {
        if (v == null) return def;
        String s = String.valueOf(v).trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) return def;
        try {
            return SemanticZoneType.valueOf(s);
        } catch (Exception ignored) {
            return def;
        }
    }
}


