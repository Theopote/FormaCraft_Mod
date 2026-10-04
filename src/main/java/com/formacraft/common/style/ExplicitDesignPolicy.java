package com.formacraft.common.style;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import java.util.*;

/** Explicit opt-outs are different from absent defaults and compiler-owned suppression flags. */
public final class ExplicitDesignPolicy {
    private ExplicitDesignPolicy() {}
    public static boolean none(Map<String, Object> params, String... keys) {
        if (params == null) return false;
        for (String key : keys) if (params.get(key) != null)
            return "none".equalsIgnoreCase(String.valueOf(params.get(key)).trim());
        return false;
    }
    public static boolean roofDisabled(Component component) {
        return component != null && none(component.params(), "roof_type", "roofType")
                && !Boolean.TRUE.equals(component.params().get("compiler_suppressed_roof"));
    }
    public static boolean windowsDisabled(Component component) {
        if (component == null) return false;
        if (none(component.params(), "window_style", "windowStyle")) return true;
        Double ratio = com.formacraft.common.generation.component.util.ComponentParamParsers.doubleOrNull(
                component.params(), "window_ratio", "windowRatio");
        return ratio != null && ratio <= 0;
    }
    public static boolean entranceDisabled(Component component) {
        return component != null && none(component.params(), "entrance_type", "entranceType");
    }
    public static boolean noComplexDecor(LlmPlan plan, Map<String, Object> params) {
        if (params != null && (Boolean.TRUE.equals(params.get("no_complex_decor"))
                || none(params, "decor_style", "decoration_style"))) return true;
        if (plan == null || plan.proportionHints() == null) return false;
        var hints = plan.proportionHints();
        if (Boolean.TRUE.equals(hints.get("no_complex_decor"))) return true;
        if (!(hints.get("building_contract") instanceof Map<?, ?> contract)
                || !(contract.get("requirements") instanceof List<?> requirements)) return false;
        for (var raw : requirements) {
            if (!(raw instanceof Map<?, ?> requirement) || !"no_complex_decor".equals(requirement.get("property"))
                    || !Boolean.TRUE.equals(requirement.get("value"))) continue;
            Object scope = requirement.get("scope");
            if ("plan".equals(scope) || "all_main_masses".equals(scope)) return true;
            if (params != null) {
                if (scope != null && scope.equals(params.get("requirement_scope"))) return true;
                if (requirement.get("target_components") instanceof List<?> targets
                        && (targets.contains(params.get("component_id")) || targets.contains(params.get("host_id")))) return true;
            }
        }
        return false;
    }
}
