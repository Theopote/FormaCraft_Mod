package com.formacraft.common.skeleton;

import java.util.HashMap;
import java.util.Map;

/**
 * Parameters for skeleton generation.
 * v1 uses a typed-ish map to keep it flexible while we iterate.
 */
public final class SkeletonParams {
    private final Map<String, Object> values = new HashMap<>();

    public SkeletonParams put(String key, Object value) {
        values.put(key, value);
        return this;
    }

    public Object get(String key) {
        return values.get(key);
    }

    public Map<String, Object> asMap() {
        return values;
    }
}


