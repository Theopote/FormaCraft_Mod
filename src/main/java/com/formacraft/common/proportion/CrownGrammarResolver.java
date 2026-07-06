package com.formacraft.common.proportion;

import com.formacraft.common.generation.component.util.CrownTemplateLibrary;
import com.formacraft.common.generation.component.util.RevolveProfileParser;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * proportion_hints / typology → crown_template 注入。
 */
public final class CrownGrammarResolver {

    private CrownGrammarResolver() {}

    public static Component apply(LlmPlan plan, Component component) {
        if (plan == null || component == null) {
            return component;
        }
        String type = normalizeType(component.componentType());
        if (!"CROWN".equals(type)) {
            return component;
        }

        Map<String, Object> hints = plan.proportionHints() != null ? plan.proportionHints() : Map.of();
        Map<String, Object> params = new HashMap<>();
        if (component.params() != null) {
            params.putAll(component.params());
        }
        boolean changed = false;

        String template = getParamString(params, "crown_template", "crownTemplate");
        if (isBlank(template)) {
            Object hint = hints.get("crown_template");
            if (hint == null) {
                hint = hints.get("crownTemplate");
            }
            if (hint != null && !String.valueOf(hint).isBlank()) {
                params.put("crown_template", CrownTemplateLibrary.normalize(String.valueOf(hint)));
                changed = true;
            } else {
                String typology = String.valueOf(hints.getOrDefault("typology", "")).toLowerCase(Locale.ROOT);
                if (typology.contains("baroque") || typology.contains("orthodox") || typology.contains("onion")) {
                    params.put("crown_template", CrownTemplateLibrary.ONION_DOME);
                    changed = true;
                } else if (typology.contains("classical") || typology.contains("monument")
                        || typology.contains("palace") || typology.contains("castle")) {
                    params.put("crown_template", CrownTemplateLibrary.CLASSICAL_CUPOLA);
                    changed = true;
                }
            }
        } else {
            String normalized = CrownTemplateLibrary.normalize(template);
            if (!template.equalsIgnoreCase(normalized)) {
                params.put("crown_template", normalized);
                changed = true;
            }
        }

        if (!params.containsKey("revolve_segments")) {
            params.put("revolve_segments", 32);
            changed = true;
        }

        if (!hasProfileParam(params)) {
            Object profileHint = firstHint(hints,
                    "profile_curve_y_radius", "profileCurveYRadius",
                    "profile_curve", "profileCurve", "crown_profile", "crownProfile");
            if (profileHint != null) {
                params.put("profile_curve_y_radius", profileHint);
                changed = true;
            }
        }

        if (!changed) {
            return component;
        }
        return new Component(
                component.componentType(),
                component.slotId(),
                component.relativePosition(),
                component.dimensions(),
                component.features(),
                params
        );
    }

    private static String normalizeType(String type) {
        if (type == null) {
            return "";
        }
        return type.trim().toUpperCase(Locale.ROOT);
    }

    private static String getParamString(Map<String, Object> params, String... keys) {
        if (params == null) {
            return null;
        }
        for (String key : keys) {
            Object v = params.get(key);
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean hasProfileParam(Map<String, Object> params) {
        if (params == null) {
            return false;
        }
        return RevolveProfileParser.resolve(params) != null;
    }

    private static Object firstHint(Map<String, Object> hints, String... keys) {
        if (hints == null) {
            return null;
        }
        for (String key : keys) {
            Object value = hints.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
