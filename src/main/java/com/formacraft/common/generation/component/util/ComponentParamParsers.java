package com.formacraft.common.generation.component.util;

import com.formacraft.common.generation.design.DesignParameterRegistry;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.logging.FcaLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared coercion helpers for component generator params maps.
 */
public final class ComponentParamParsers {
    private ComponentParamParsers() {}

    private static final FcaLog LOG = FcaLog.of("ComponentParams");

    /** Lookup keys for a canonical design param (snake_case first, then registry aliases). */
    public static String[] keysFor(String canonicalParam, String... extraKeys) {
        List<String> keys = new ArrayList<>();
        String canonical = DesignParameterRegistry.canonicalKey(canonicalParam);
        if (canonical != null && !canonical.isBlank()) {
            keys.add(canonical);
        }
        for (String alias : DesignParameterRegistry.aliases(canonical)) {
            if (!keys.contains(alias)) {
                keys.add(alias);
            }
        }
        if (extraKeys != null) {
            for (String extra : extraKeys) {
                if (extra != null && !extra.isBlank() && !keys.contains(extra)) {
                    keys.add(extra);
                }
            }
        }
        return keys.toArray(String[]::new);
    }

    public static int intParam(Map<String, Object> params, String... keys) {
        return intParam(params, 0, keys);
    }

    public static int intParam(Map<String, Object> params, int fallback, String... keys) {
        Integer parsed = intOrNull(params, keys);
        return parsed != null ? parsed : fallback;
    }

    public static Integer intOrNull(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) return null;
        for (String key : keys) {
            if (key == null) continue;
            Object v = params.get(key);
            if (v == null) continue;
            if (v instanceof Number n) return n.intValue();
            try {
                return Integer.parseInt(String.valueOf(v).trim());
            } catch (NumberFormatException e) {
                LOG.debug("parse int failed key={} value={}", key, v);
            }
        }
        return null;
    }

    public static Double doubleOrNull(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) return null;
        for (String key : keys) {
            if (key == null) continue;
            Object v = params.get(key);
            if (v == null) continue;
            if (v instanceof Number n) return n.doubleValue();
            try {
                return Double.parseDouble(String.valueOf(v).trim());
            } catch (NumberFormatException e) {
                LOG.debug("parse double failed key={} value={}", key, v);
            }
        }
        return null;
    }

    /**
     * Read a non-blank string param. Skips JSON null entries (unlike {@link Map#getOrDefault}),
     * blank strings, and the literal {@code "null"} produced by {@code String.valueOf(null)}.
     */
    public static String stringParam(Map<String, Object> params, String fallback, String... keys) {
        String parsed = stringOrNull(params, keys);
        return parsed != null ? parsed : fallback;
    }

    public static String stringOrNull(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) return null;
        for (String key : keys) {
            if (key == null) continue;
            Object v = params.get(key);
            if (v == null) continue;
            String s = String.valueOf(v).trim();
            if (s.isEmpty() || "null".equalsIgnoreCase(s)) continue;
            return s;
        }
        return null;
    }

    /** Normalize facing tokens; illegal values keep {@code fallback}. */
    public static GlobalConstraints.Facing facingParam(
            Map<String, Object> params, GlobalConstraints.Facing fallback, String... keys) {
        GlobalConstraints.Facing parsed = facingOrNull(params, keys);
        return parsed != null ? parsed : fallback;
    }

    public static GlobalConstraints.Facing facingOrNull(Map<String, Object> params, String... keys) {
        String raw = stringOrNull(params, keys);
        if (raw == null) return null;
        String s = raw.toUpperCase(Locale.ROOT);
        return switch (s) {
            case "NORTH", "N" -> GlobalConstraints.Facing.NORTH;
            case "SOUTH", "S" -> GlobalConstraints.Facing.SOUTH;
            case "EAST", "E" -> GlobalConstraints.Facing.EAST;
            case "WEST", "W" -> GlobalConstraints.Facing.WEST;
            default -> {
                LOG.debug("parse facing failed value={}", raw);
                yield null;
            }
        };
    }
}
