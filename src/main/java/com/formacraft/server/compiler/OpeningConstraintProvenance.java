package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stamps opening field-level {@code constraint_sources} from request_intent.
 * Component-level {@code constraint_source} alone never blocks upgrades from user text.
 */
final class OpeningConstraintProvenance {
    private OpeningConstraintProvenance() {}

    static LlmPlan stamp(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return plan;
        }
        Map<String, Object> intent = requestIntent(plan);
        if (intent == null || intent.isEmpty()) {
            return plan;
        }
        boolean doorWidthUser = truthy(intent.get("door_width_explicit"))
                || truthy(nested(intent, "openings", "door_width_explicit"));
        boolean upperEntranceUser = truthy(intent.get("upper_entrance_explicit"))
                || truthy(nested(intent, "openings", "upper_entrance_explicit"));
        boolean windowMaterialUser = truthy(intent.get("window_material_explicit"))
                || truthy(nested(intent, "openings", "window_material_explicit"));
        String windowBlock = materialSlot(intent, "window", "glass");

        boolean changed = false;
        List<Component> out = new ArrayList<>(plan.components().size());
        for (Component c : plan.components()) {
            Component stamped = stampOne(
                    c, plan, doorWidthUser, upperEntranceUser, windowMaterialUser, windowBlock);
            if (stamped != c) changed = true;
            out.add(stamped);
        }
        if (!changed) return plan;
        return withComponents(plan, List.copyOf(out));
    }

    private static Component stampOne(
            Component c,
            LlmPlan plan,
            boolean doorWidthUser,
            boolean upperEntranceUser,
            boolean windowMaterialUser,
            String windowBlock
    ) {
        if (c == null || c.params() == null || c.params().isEmpty()) return c;

        String type = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
        Map<String, Object> params = new HashMap<>(c.params());
        @SuppressWarnings("unchecked")
        Map<String, Object> fieldSources = params.get("constraint_sources") instanceof Map<?, ?> existing
                ? new HashMap<>((Map<String, Object>) existing)
                : new HashMap<>();
        boolean mutated = false;
        boolean entranceLike = type.contains("ENTRANCE") || type.contains("DOOR") || type.isEmpty();

        boolean hasDoorWidth = params.get("door_width") != null || params.get("doorWidth") != null;
        if (hasDoorWidth && entranceLike) {
            boolean sideDoor = looksLikeSideEntrance(c);
            if (doorWidthUser && !sideDoor) {
                params.put("door_width_user_explicit", true);
                fieldSources.put("door_width", "USER_EXPLICIT");
                // Legacy component-level tag for material audits.
                if (!"USER_EXPLICIT".equalsIgnoreCase(String.valueOf(
                        params.getOrDefault("constraint_source", "")))) {
                    params.put("constraint_source", "USER_EXPLICIT");
                }
            } else if (!fieldSources.containsKey("door_width")) {
                fieldSources.put("door_width", "LLM_INFERRED");
                if (!hasConstraintSource(params)) {
                    params.put("constraint_source", "LLM_INFERRED");
                }
            }
            mutated = true;
        }
        // 「二层入口」 only when this entrance actually sits on/above the second storey.
        if (upperEntranceUser && entranceLike && !looksLikeSideEntrance(c)
                && isUpperStoreyEntrance(c, plan)) {
            params.put("door_presence_explicit", true);
            params.put("upper_entrance_explicit", true);
            fieldSources.put("opening_presence", "USER_EXPLICIT");
            params.put("constraint_source", "USER_EXPLICIT");
            mutated = true;
        }

        String glass = firstString(params, "glass_block", "glazing_block", "glass_material", "window_block");
        boolean windowLike = type.contains("WINDOW") || type.contains("FACADE") || glass != null;
        if (windowLike && windowMaterialUser && windowBlock != null && !windowBlock.isBlank()) {
            // Authority value from player text — never stamp LLM's wrong glass as USER_EXPLICIT.
            if (glass == null || !blockEquals(windowBlock, glass)) {
                params.put("glass_block", windowBlock);
                glass = windowBlock;
                mutated = true;
            }
            if (blockEquals(windowBlock, glass)) {
                fieldSources.put("glass_block", "USER_EXPLICIT");
                params.put("window_explicit", true);
                params.put("constraint_source", "USER_EXPLICIT");
                mutated = true;
            }
        } else if (windowLike && glass != null && windowBlock != null && blockEquals(windowBlock, glass)) {
            fieldSources.put("glass_block", "USER_EXPLICIT");
            params.put("window_explicit", true);
            params.put("constraint_source", "USER_EXPLICIT");
            mutated = true;
        }

        if (!fieldSources.isEmpty()) {
            params.put("constraint_sources", fieldSources);
            mutated = true;
        }

        if (!mutated) return c;
        return new Component(
                c.componentType(), c.slotId(), c.relativePosition(), c.dimensions(), c.features(), params);
    }

    /** True when entrance Y is at/above the host's second storey band. */
    static boolean isUpperStoreyEntrancePublic(Component door, LlmPlan plan) {
        return isUpperStoreyEntrance(door, plan);
    }

    /** True when entrance Y is at/above the host's second storey band. */
    private static boolean isUpperStoreyEntrance(Component door, LlmPlan plan) {
        if (door == null || door.relativePosition() == null || plan == null || plan.components() == null) {
            return false;
        }
        Component host = findHostMass(door, plan.components());
        if (host == null || host.relativePosition() == null) {
            // Without a host, do not stamp upper presence onto a possible ground door.
            return door.relativePosition().y() >= 3;
        }
        int groundY = host.relativePosition().y();
        int fh = 4;
        if (host.params() != null) {
            Object fhObj = host.params().get("floor_height");
            if (fhObj == null) fhObj = host.params().get("floorHeight");
            if (fhObj instanceof Number n) fh = Math.max(3, n.intValue());
            Object ground = host.params().get("ground_clear_height");
            if (ground instanceof Number n) fh = Math.max(fh, n.intValue());
        }
        int secondStoreyY = groundY + fh;
        return door.relativePosition().y() >= secondStoreyY;
    }

    private static Component findHostMass(Component door, List<Component> parts) {
        if (door.params() == null) return null;
        Object hostId = door.params().get("host_id");
        if (hostId == null) hostId = door.params().get("hostId");
        if (hostId == null) return null;
        String want = String.valueOf(hostId).trim();
        for (Component c : parts) {
            if (c == null || c.params() == null) continue;
            Object id = c.params().get("component_id");
            if (id != null && want.equals(String.valueOf(id).trim())) return c;
        }
        return null;
    }

    private static boolean hasConstraintSource(Map<String, Object> params) {
        Object src = params.get("constraint_source");
        if (src == null) src = params.get("constraintSource");
        return src != null && !String.valueOf(src).isBlank();
    }

    private static boolean looksLikeSideEntrance(Component c) {
        String slot = c.slotId() == null ? "" : c.slotId().toLowerCase(Locale.ROOT);
        if (slot.contains("side") || slot.contains("back") || slot.contains("balcony")
                || slot.contains("侧") || slot.contains("后门") || slot.contains("旁门")) {
            return true;
        }
        if (c.params() == null) return false;
        for (String key : List.of("entrance_role", "entranceRole", "door_role", "component_id", "entrance_id")) {
            Object v = c.params().get(key);
            if (v == null) continue;
            String s = String.valueOf(v).toLowerCase(Locale.ROOT);
            if (s.contains("side") || s.contains("back") || s.contains("balcony")
                    || s.contains("侧") || s.contains("旁")) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requestIntent(LlmPlan plan) {
        if (plan.planMeta() == null) return null;
        Object ri = plan.planMeta().get("request_intent");
        if (ri instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String materialSlot(Map<String, Object> intent, String... slots) {
        Object mats = intent.get("materials");
        if (!(mats instanceof Map<?, ?> map)) return null;
        Map<String, Object> materials = (Map<String, Object>) map;
        for (String slot : slots) {
            Object v = materials.get(slot);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v).trim();
        }
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

    private static String firstString(Map<String, Object> params, String... keys) {
        for (String key : keys) {
            Object v = params.get(key);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v).trim();
        }
        return null;
    }

    private static boolean blockEquals(String a, String b) {
        return normalizeBlock(a).equals(normalizeBlock(b));
    }

    private static String normalizeBlock(String value) {
        String id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        int bracket = id.indexOf('[');
        if (bracket >= 0) id = id.substring(0, bracket);
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private static LlmPlan withComponents(LlmPlan plan, List<Component> components) {
        return new LlmPlan(
                plan.mode(),
                plan.styleProfile(),
                plan.anchor(),
                plan.globalConstraints(),
                plan.layout(),
                components,
                plan.genome(),
                plan.styleAttributes(),
                plan.proportionHints(),
                plan.alignmentAndSymmetry(),
                plan.targetSlotId(),
                plan.allowedArea(),
                plan.patch(),
                plan.planProgram(),
                plan.planSkeleton(),
                plan.planStatus(),
                plan.error(),
                plan.capabilityGap(),
                plan.playerFidelityNoticeZh(),
                plan.distinguishingFeatures(),
                plan.enrichmentGuard(),
                plan.planMeta(),
                plan.designSources());
    }
}
