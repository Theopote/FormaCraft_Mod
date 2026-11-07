package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Final existence check: when the player required an upper-storey entrance, at least one
 * ENTRANCE must actually sit on/above the second storey (not merely a ground door).
 */
final class UpperStoreyEntranceAudit {
    private UpperStoreyEntranceAudit() {}

    static Optional<CapabilityGap> check(LlmPlan plan) {
        if (plan == null || plan.planMeta() == null) return Optional.empty();
        Map<String, Object> intent = requestIntent(plan);
        if (intent == null) return Optional.empty();
        boolean required = truthy(intent.get("upper_entrance_explicit"))
                || truthy(nested(intent, "openings", "upper_entrance_explicit"));
        if (!required) return Optional.empty();
        if (plan.components() == null || plan.components().isEmpty()) {
            return Optional.of(gap("plan has no components"));
        }
        boolean found = false;
        for (Component c : plan.components()) {
            if (c == null || c.componentType() == null) continue;
            String t = c.componentType().trim().toUpperCase(Locale.ROOT);
            if (!t.contains("ENTRANCE") && !t.contains("DOOR")) continue;
            if (OpeningConstraintProvenance.isUpperStoreyEntrancePublic(c, plan)) {
                found = true;
                break;
            }
        }
        if (found) return Optional.empty();
        return Optional.of(gap("player required upper-storey entrance but only ground-level doors exist"));
    }

    private static CapabilityGap gap(String detail) {
        return new CapabilityGap(
                "E_UPPER_ENTRANCE_MISSING",
                "二层入口要求未满足",
                detail,
                java.util.List.of("upper_entrance_explicit"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requestIntent(LlmPlan plan) {
        Object ri = plan.planMeta().get("request_intent");
        if (ri instanceof Map<?, ?> map) return (Map<String, Object>) map;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Object nested(Map<String, Object> root, String a, String b) {
        Object mid = root.get(a);
        if (!(mid instanceof Map<?, ?> map)) return null;
        return ((Map<String, Object>) map).get(b);
    }

    private static boolean truthy(Object v) {
        if (v instanceof Boolean b) return b;
        if (v == null) return false;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }
}
