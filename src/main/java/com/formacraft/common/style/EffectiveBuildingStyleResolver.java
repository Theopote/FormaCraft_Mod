package com.formacraft.common.style;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared effective style for inference and emit: component-local {@code style_profile}, then host
 * chain / building root, then plan-level defaults. Mixed compounds keep distinct languages per building.
 */
public final class EffectiveBuildingStyleResolver {
    private EffectiveBuildingStyleResolver() {}

    public static String resolve(LlmPlan plan, Component component) {
        String local = localStyleProfile(component);
        if (local != null) {
            return local;
        }
        if (plan != null && plan.components() != null && !plan.components().isEmpty()) {
            String viaHost = resolveViaHostChain(component, plan.components());
            if (viaHost != null) {
                return viaHost;
            }
        }
        return plan == null ? null : plan.styleProfile();
    }

    /**
     * Local or host-chain style only — never plan defaults. Used so detectFlavor can classify a
     * component's own architectural language before merging plan-wide decorative keywords.
     */
    public static String resolveLocalOrHost(LlmPlan plan, Component component) {
        String local = localStyleProfile(component);
        if (local != null) {
            return local;
        }
        if (plan != null && plan.components() != null && !plan.components().isEmpty()) {
            return resolveViaHostChain(component, plan.components());
        }
        return null;
    }

    public static String localStyleProfile(Component component) {
        if (component == null || component.params() == null) {
            return null;
        }
        return firstNonBlank(component.params(), "style_profile", "effective_style_profile");
    }

    /** Copy host local style onto a derived satellite when the satellite has none yet. */
    public static void inheritLocalStyle(Map<String, Object> params, Component host) {
        if (params == null || host == null) {
            return;
        }
        if (firstNonBlank(params, "style_profile", "effective_style_profile") != null) {
            return;
        }
        String local = localStyleProfile(host);
        if (local != null) {
            params.put("style_profile", local);
        }
    }

    private static String resolveViaHostChain(Component component, List<Component> all) {
        if (component == null || all == null || all.isEmpty()) {
            return null;
        }
        // Unique component_id only — duplicate ids must not last-writer-win across buildings.
        Map<String, Component> byId = new HashMap<>();
        Set<String> duplicateIds = new HashSet<>();
        Map<String, String> parentById = new HashMap<>();
        for (Component c : all) {
            if (c == null || c.params() == null) continue;
            String id = firstNonBlank(c.params(), "component_id", "componentId");
            if (id == null) continue;
            if (duplicateIds.contains(id)) {
                continue;
            }
            if (byId.containsKey(id)) {
                byId.remove(id);
                parentById.remove(id);
                duplicateIds.add(id);
                continue;
            }
            byId.put(id, c);
            String host = firstNonBlank(c.params(), "host_id", "hostId");
            if (host != null) {
                parentById.put(id, host);
            }
        }
        String cursor = component.params() == null
                ? null
                : firstNonBlank(component.params(), "host_id", "hostId");
        Set<String> seen = new HashSet<>();
        while (cursor != null && seen.add(cursor)) {
            if (duplicateIds.contains(cursor)) {
                return null; // ambiguous host target — do not invent style inheritance
            }
            Component host = byId.get(cursor);
            if (host != null) {
                String style = localStyleProfile(host);
                if (style != null) {
                    return style;
                }
            }
            cursor = parentById.get(cursor);
        }
        return null;
    }

    private static String firstNonBlank(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object v = params.get(key);
            if (v == null) continue;
            String s = String.valueOf(v).trim();
            if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) {
                return s;
            }
        }
        return null;
    }
}
