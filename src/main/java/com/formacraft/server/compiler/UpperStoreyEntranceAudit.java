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
 * must share the same host (or sit adjacent to that door) — a stair on another building does not count.
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
        Component upperDoor = null;
        for (Component c : plan.components()) {
            if (c == null || c.componentType() == null) continue;
            String t = c.componentType().trim().toUpperCase(Locale.ROOT);
            if (!t.contains("ENTRANCE") && !t.contains("DOOR")) continue;
            if (OpeningConstraintProvenance.isUpperStoreyEntrancePublic(c, plan)) {
                upperDoor = c;
                break;
            }
        }
        if (upperDoor == null) {
            return Optional.of(gap("player required upper-storey entrance but only ground-level doors exist"));
        }
        boolean requiresAccess = truthy(intent.get("upper_entrance_requires_access"))
                || truthy(nested(intent, "openings", "upper_entrance_requires_access"));
        if (requiresAccess && !hasExternalAccessLinkFor(plan, upperDoor)) {
            return Optional.of(gap(
                    "player required upper-storey entrance linked to exterior stairs/bridge/balcony, "
                            + "but no access circulation component exists on the same host"));
        }
        return Optional.empty();
    }

    static boolean hasExternalAccessLinkFor(LlmPlan plan, Component upperDoor) {
        if (plan == null || plan.components() == null || upperDoor == null) return false;
        String doorHost = hostIdOf(upperDoor);
        for (Component c : plan.components()) {
            if (!isAccessComponent(c)) continue;
            String accessHost = hostIdOf(c);
            if (doorHost != null && !doorHost.isBlank()
                    && accessHost != null && !accessHost.isBlank()
                    && doorHost.equals(accessHost)) {
                return true;
            }
            // Same slot family without host_id: accept when both omit host and share slot.
            if ((doorHost == null || doorHost.isBlank())
                    && (accessHost == null || accessHost.isBlank())
                    && upperDoor.slotId() != null
                    && upperDoor.slotId().equals(c.slotId())) {
                return true;
            }
            // Geometric adjacency fallback: access footprint overlaps or touches the door's host mass.
            if (doorHost != null && sharesHostMassProximity(plan, upperDoor, c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sharesHostMassProximity(LlmPlan plan, Component door, Component access) {
        Component host = findHost(plan, hostIdOf(door));
        if (host == null || host.relativePosition() == null || host.dimensions() == null) return false;
        if (access.relativePosition() == null) return false;
        int hx0 = host.relativePosition().x();
        int hz0 = host.relativePosition().z();
        int hx1 = hx0 + Math.max(1, host.dimensions().width()) - 1;
        int hz1 = hz0 + Math.max(1, host.dimensions().depth()) - 1;
        int ax = access.relativePosition().x();
        int az = access.relativePosition().z();
        int aw = access.dimensions() == null ? 1 : Math.max(1, access.dimensions().width());
        int ad = access.dimensions() == null ? 1 : Math.max(1, access.dimensions().depth());
        int ax1 = ax + aw - 1;
        int az1 = az + ad - 1;
        // Expand host by 2 blocks so exterior stairs just outside the wall still count.
        return ax1 >= hx0 - 2 && ax <= hx1 + 2 && az1 >= hz0 - 2 && az <= hz1 + 2;
    }

    private static Component findHost(LlmPlan plan, String hostId) {
        if (hostId == null || hostId.isBlank() || plan.components() == null) return null;
        for (Component c : plan.components()) {
            if (!BuildingHostResolver.isHostMass(c)) continue;
            if (hostId.equals(BuildingHostResolver.massId(c))) return c;
        }
        return null;
    }

    private static boolean isAccessComponent(Component c) {
        if (c == null || c.componentType() == null) return false;
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
                return r.contains("stair") || r.contains("balcony") || r.contains("bridge")
                        || r.contains("access");
            }
        }
        return false;
    }

    private static String hostIdOf(Component c) {
        if (c == null || c.params() == null) return null;
        Object host = c.params().get("host_id");
        if (host == null) host = c.params().get("hostId");
        if (host == null) host = c.params().get("building_id");
        return host == null || String.valueOf(host).isBlank() ? null : String.valueOf(host).trim();
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
