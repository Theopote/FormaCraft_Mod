package com.formacraft.common.skeleton;

import com.formacraft.common.logging.FcaLog;

import java.util.Map;

/**
 * Shared coercion helpers for skeleton parameter maps.
 */
public final class SkeletonParamParsers {
    private SkeletonParamParsers() {}

    private static final FcaLog LOG = FcaLog.of("SkeletonParams");

    public static int boundedInt(SkeletonParams params, String key, int defaultValue, int min, int max) {
        Object raw = params != null ? params.get(key) : null;
        int n = intValue(raw, defaultValue, key);
        if (n < min) n = min;
        if (n > max) n = max;
        return n;
    }

    public static int intParam(Map<String, Object> params, String key, int defaultValue) {
        if (params == null) return defaultValue;
        return intValue(params.get(key), defaultValue, key);
    }

    public static double doubleParam(Map<String, Object> params, String key, double defaultValue) {
        if (params == null) return defaultValue;
        Object raw = params.get(key);
        if (raw instanceof Number n) return n.doubleValue();
        if (raw instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (Exception e) {
                LOG.debug("parse double failed key={} value={} def={}", key, raw, defaultValue);
            }
        }
        return defaultValue;
    }

    private static int intValue(Object raw, int defaultValue, String key) {
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (Exception e) {
                LOG.debug("parse int failed key={} value={} def={}", key, raw, defaultValue);
            }
        }
        return defaultValue;
    }
}
