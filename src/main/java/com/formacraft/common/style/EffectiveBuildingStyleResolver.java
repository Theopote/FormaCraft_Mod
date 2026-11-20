package com.formacraft.common.style;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.Map;

/**
 * Shared effective style for inference and emit: component-local {@code style_profile} wins over
 * plan-level defaults so mixed compounds keep distinct roof/window/material languages per building.
 */
public final class EffectiveBuildingStyleResolver {
    private EffectiveBuildingStyleResolver() {}

    public static String resolve(LlmPlan plan, Component component) {
        String local = localStyleProfile(component);
        if (local != null) {
            return local;
        }
        return plan == null ? null : plan.styleProfile();
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
