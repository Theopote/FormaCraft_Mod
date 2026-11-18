package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.typology.TypologyRoofCapability;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Compile-time gate: plan roof_type on typology STRUCTURE must be executable by the generator.
 * Param match alone is insufficient — dense_eaves_pagoda still emits eaves when roof_type=none.
 */
final class TypologyRoofCapabilityValidator {
    private TypologyRoofCapabilityValidator() {}

    static Optional<CapabilityGap> check(LlmPlan plan, List<Component> components) {
        if (components == null) {
            return Optional.empty();
        }
        for (Component component : components) {
            if (component == null || component.componentType() == null) continue;
            if (!"STRUCTURE".equalsIgnoreCase(component.componentType().trim())) continue;
            String typologyId = typologyId(component);
            if (typologyId == null || typologyId.isBlank()) continue;
            String roofType = roofType(component);
            if (roofType == null || roofType.isBlank()) continue;
            var support = TypologyRoofCapability.evaluate(typologyId, roofType);
            if (support != TypologyRoofCapability.Support.UNSUPPORTED) continue;
            String reason = TypologyRoofCapability.unsupportedReason(typologyId, roofType)
                    .orElse("typology roof_type unsupported");
            String cid = componentId(component);
            return Optional.of(new CapabilityGap(
                    "E_TYPOLOGY_ROOF_UNSUPPORTED",
                    reason,
                    cid == null ? "components[]" : "components[" + cid + "].params.roof_type",
                    List.of(
                            "Use a typology that can execute this roof_type",
                            "Or remove the explicit roof_type and keep the typology's intrinsic roof morphology",
                            "Do not treat params.roof_type equality as generation success"
                    ),
                    Map.of(
                            "typology_id", typologyId,
                            "requested", roofType,
                            "component_id", cid == null ? "" : cid
                    )
            ));
        }
        return Optional.empty();
    }

    private static String typologyId(Component component) {
        if (component.features() != null) {
            for (String feat : component.features()) {
                if (feat == null) continue;
                String text = feat.trim();
                if (text.toLowerCase(Locale.ROOT).startsWith("typology:")) {
                    return text.substring("typology:".length()).trim();
                }
            }
        }
        if (component.params() == null) return null;
        Object tid = first(component.params(), "typology_id", "typologyId", "structural_typology");
        return tid == null ? null : String.valueOf(tid).trim();
    }

    private static String roofType(Component component) {
        if (component.params() == null) return null;
        Object v = first(component.params(), "roof_type", "roofType");
        return v == null ? null : String.valueOf(v).trim();
    }

    private static String componentId(Component component) {
        if (component.params() == null) return null;
        Object id = first(component.params(), "component_id", "componentId");
        return id == null || String.valueOf(id).isBlank() ? null : String.valueOf(id).trim();
    }

    private static Object first(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key) && map.get(key) != null) return map.get(key);
        }
        return null;
    }
}
