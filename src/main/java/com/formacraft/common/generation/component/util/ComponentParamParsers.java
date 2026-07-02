package com.formacraft.common.generation.component.util;

import com.formacraft.common.logging.FcaLog;

import java.util.Map;

/**
 * Shared coercion helpers for component generator params maps.
 */
public final class ComponentParamParsers {
    private ComponentParamParsers() {}

    private static final FcaLog LOG = FcaLog.of("ComponentParams");

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
}
