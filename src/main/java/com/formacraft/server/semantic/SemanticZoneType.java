package com.formacraft.server.semantic;

import com.formacraft.common.logging.FcaLog;

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

    private static final FcaLog LOG = FcaLog.of("SemanticZoneType");

    public static SemanticZoneType parse(Object v, SemanticZoneType def) {
        if (v == null) return def;
        String s = String.valueOf(v).trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) return def;
        try {
            return SemanticZoneType.valueOf(s);
        } catch (Exception ex) {
            LOG.debug("parse semantic zone failed value={} def={}", s, def, ex);
            return def;
        }
    }
}