package com.formacraft.common.generation.routing;

import com.formacraft.common.model.build.BuildingSpec;

import java.util.Map;

/** Dev/test-only silent house/tower substitution. */
public final class LegacyStructureFallbackPolicy {
    private LegacyStructureFallbackPolicy() {}

    public static boolean allow(BuildingSpec spec) {
        if (spec == null) {
            return false;
        }
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) {
            return false;
        }
        return Boolean.TRUE.equals(extra.get("allow_legacy_fallback"));
    }
}
