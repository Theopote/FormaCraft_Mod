package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stamps opening {@code constraint_source} from request_intent — never from bare param presence.
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
        boolean windowMaterialUser = truthy(intent.get("window_material_explicit"))
                || truthy(nested(intent, "openings", "window_material_explicit"));
        String windowBlock = materialSlot(intent, "window", "glass");

        boolean changed = false;
        List<Component> out = new ArrayList<>(plan.components().size());
        for (Component c : plan.components()) {
            Component stamped = stampOne(c, doorWidthUser, windowMaterialUser, windowBlock);
            if (stamped != c) changed = true;
            out.add(stamped);
        }
        if (!changed) return plan;
        return withComponents(plan, List.copyOf(out));
    }

    private static Component stampOne(
            Component c,
            boolean doorWidthUser,
            boolean windowMaterialUser,
            String windowBlock
    ) {
        if (c == null || c.params() == null || c.params().isEmpty()) return c;
        if (hasConstraintSource(c.params())) return c;

        String type = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
        Map<String, Object> params = new HashMap<>(c.params());
        boolean mutated = false;

        boolean hasDoorWidth = params.get("door_width") != null || params.get("doorWidth") != null;
        if (hasDoorWidth && (type.contains("ENTRANCE") || type.contains("DOOR") || type.isEmpty())) {
            if (doorWidthUser) {
                params.put("constraint_source", "USER_EXPLICIT");
                params.put("door_explicit", true);
            } else {
                params.put("constraint_source", "LLM_INFERRED");
            }
            mutated = true;
        }

        String glass = firstString(params, "glass_block", "glazing_block", "glass_material", "window_block");
        if (glass != null && (type.contains("WINDOW") || type.contains("FACADE") || glass != null)) {
            boolean match = windowMaterialUser
                    || (windowBlock != null && blockEquals(windowBlock, glass));
            if (match && !hasConstraintSource(params)) {
                params.put("constraint_source", "USER_EXPLICIT");
                params.put("window_explicit", true);
                mutated = true;
            }
        }

        if (!mutated) return c;
        return new Component(
                c.componentType(), c.slotId(), c.relativePosition(), c.dimensions(), c.features(), params);
    }

    private static boolean hasConstraintSource(Map<String, Object> params) {
        Object src = params.get("constraint_source");
        if (src == null) src = params.get("constraintSource");
        return src != null && !String.valueOf(src).isBlank();
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
