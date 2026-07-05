package com.formacraft.common.proportion;

import com.formacraft.common.generation.component.util.ComponentRoofSpecialtyDecorator;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * proportion_hints / typology → roof_type=mansard、roof_dormers 注入。
 */
public final class RoofGrammarResolver {

    private RoofGrammarResolver() {}

    public static Component apply(LlmPlan plan, Component component) {
        if (plan == null || component == null) {
            return component;
        }
        String type = normalizeType(component.componentType());
        if (!type.startsWith("ROOF")) {
            return component;
        }

        Map<String, Object> hints = plan.proportionHints() != null ? plan.proportionHints() : Map.of();
        Map<String, Object> params = new HashMap<>();
        if (component.params() != null) {
            params.putAll(component.params());
        }
        boolean changed = false;

        ComponentRoofSpecialtyDecorator.SpecialtyLevel level =
                ComponentRoofSpecialtyDecorator.resolveLevel(plan, params, component);

        String roofType = getParamString(params, "roof_type", "roofType");
        if (level == ComponentRoofSpecialtyDecorator.SpecialtyLevel.MANSARD
                || level == ComponentRoofSpecialtyDecorator.SpecialtyLevel.MANSARD_DORMER) {
            if (isBlank(roofType) || !roofType.toLowerCase(Locale.ROOT).contains("mansard")) {
                params.put("roof_type", "mansard");
                changed = true;
            }
        }
        if (level == ComponentRoofSpecialtyDecorator.SpecialtyLevel.MANSARD_DORMER) {
            if (!isEnabled(params.get("roof_dormers")) && !isEnabled(params.get("dormers"))) {
                params.put("roof_dormers", true);
                changed = true;
            }
            Object specialty = params.get("roof_specialty");
            if (specialty == null || String.valueOf(specialty).isBlank()) {
                params.put("roof_specialty", "mansard_dormer");
                changed = true;
            }
        } else if (level == ComponentRoofSpecialtyDecorator.SpecialtyLevel.MANSARD) {
            Object specialty = params.get("roof_specialty");
            if (specialty == null || String.valueOf(specialty).isBlank()) {
                params.put("roof_specialty", "mansard");
                changed = true;
            }
        }

        if (isBlank(getParamString(params, "roof_specialty", "roofSpecialty"))) {
            Object hint = hints.get("roof_specialty");
            if (hint == null) {
                hint = hints.get("roofSpecialty");
            }
            if (hint != null && !String.valueOf(hint).isBlank()) {
                params.put("roof_specialty", String.valueOf(hint).trim());
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

    private static boolean isEnabled(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v == null) {
            return false;
        }
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return "true".equals(s) || "yes".equals(s) || "on".equals(s);
    }
}
