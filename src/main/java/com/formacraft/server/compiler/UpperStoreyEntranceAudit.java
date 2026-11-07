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
 * When the player also required stair/bridge/balcony access, a matching circulation component
 * must be present — a floating second-storey door alone is not enough.
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
        boolean foundUpper = false;
        for (Component c : plan.components()) {
            if (c == null || c.componentType() == null) continue;
            String t = c.componentType().trim().toUpperCase(Locale.ROOT);
            if (!t.contains("ENTRANCE") && !t.contains("DOOR")) continue;
            if (OpeningConstraintProvenance.isUpperStoreyEntrancePublic(c, plan)) {
                foundUpper = true;
                break;
            }
        }
        if (!foundUpper) {
            return Optional.of(gap("player required upper-storey entrance but only ground-level doors exist"));
        }
        boolean requiresAccess = truthy(intent.get("upper_entrance_requires_access"))
                || truthy(nested(intent, "openings", "upper_entrance_requires_access"));
        if (requiresAccess && !hasExternalAccessLink(plan)) {
            return Optional.of(gap(
                    "player required upper-storey entrance linked to exterior stairs/bridge/balcony, "
                            + "but no access circulation component exists"));
        }
        return Optional.empty();
    }

    /** Stairs, balcony, bridge, platform, ramp, or corridor that can serve as external access. */
    static boolean hasExternalAccessLink(LlmPlan plan) {
        if (plan == null || plan.components() == null) return false;
        for (Component c : plan.components()) {
            if (c == null || c.componentType() == null) continue;
            String t = c.componentType().trim().toUpperCase(Locale.ROOT);
            if (t.contains("STAIR") || t.contains("BALCONY") || t.contains("BRIDGE")
                    || t.contains("PLATFORM") || t.contains("RAMP") || t.contains("CORRIDOR")
                    || t.contains("GALLERY") || t.contains("WALKWAY")) {
                return true;
            }
            if (c.features() != null) {
                for (String f : c.features()) {
                    if (f == null) continue;
                    String fl = f.toLowerCase(Locale.ROOT);
                    if (fl.contains("stair") || fl.contains("balcony") || fl.contains("bridge")
                            || fl.contains("platform") || fl.contains("exterior_access")) {
                        return true;
                    }
                }
            }
            if (c.params() != null) {
                Object role = c.params().get("role");
                if (role == null) role = c.params().get("circulation_role");
                if (role != null) {
                    String r = String.valueOf(role).toLowerCase(Locale.ROOT);
                    if (r.contains("stair") || r.contains("balcony") || r.contains("bridge")
                            || r.contains("access")) {
                        return true;
                    }
                }
                if (Boolean.TRUE.equals(c.params().get("exterior"))
                        && (t.contains("STAIR") || String.valueOf(c.params().get("type"))
                        .toLowerCase(Locale.ROOT).contains("stair"))) {
                    return true;
                }
            }
        }
        return false;
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
