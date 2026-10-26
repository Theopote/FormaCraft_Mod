package com.formacraft.common.generation.component.util;

import java.util.Map;
import java.util.Set;

/** Shared support vocabulary; structural posts must not inherit ornament height limits. */
public final class StructuralSupportPolicy {
    private StructuralSupportPolicy() {}
    public static boolean isColumn(Map<String,Object> p) {
        if (p == null) return false;
        for (String key : new String[]{"role", "part"}) {
            Object value=p.get(key);
            if(value!=null && Set.of("column","pillar","post","stilt_post","stilt_piling","support_column")
                    .contains(value.toString().toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return p.containsKey("supports") || Boolean.TRUE.equals(p.get("supports_roof"));
    }
}
