package com.formacraft.common.skeleton;

import com.formacraft.common.logging.FcaLog;

/**
 * Shared coercion helpers for skeleton parameter maps.
 */
public final class SkeletonParamParsers {
    private SkeletonParamParsers() {}

    private static final FcaLog LOG = FcaLog.of("SkeletonParams");

    public static int boundedInt(SkeletonParams params, String key, int defaultValue, int min, int max) {
        Object raw = params != null ? params.get(key) : null;
        int n = defaultValue;
        try {
            if (raw instanceof Number num) n = num.intValue();
            else if (raw != null) n = Integer.parseInt(String.valueOf(raw).trim());
        } catch (Exception e) {
            LOG.debug("parse int failed key={} value={} def={}", key, raw, defaultValue);
        }
        if (n < min) n = min;
        if (n > max) n = max;
        return n;
    }
}
