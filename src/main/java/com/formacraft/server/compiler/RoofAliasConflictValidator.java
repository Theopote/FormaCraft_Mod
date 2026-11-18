package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.typology.TypologyParamResolver;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dual USER_EXPLICIT roofType/roof_type conflicts must not silently pick a winner.
 * Classification: HARD_CONSTRAINT provenance — TypologyParamResolver marks the conflict;
 * this gate promotes it to {@code E_ROOF_ALIAS_CONFLICT}.
 */
final class RoofAliasConflictValidator {
    private RoofAliasConflictValidator() {}

    static Optional<CapabilityGap> check(LlmPlan plan, List<Component> components) {
        if (components == null) return Optional.empty();
        for (Component c : components) {
            if (c == null || c.params() == null) continue;
            if (truthy(c.params().get("roof_alias_conflict"))) {
                return conflictGap();
            }
            Object raw = c.params().get(TypologyParamResolver.PARAM_SOURCES_KEY);
            if (raw instanceof Map<?, ?> sources
                    && (sources.containsKey("roofType_alias_conflict")
                    || sources.containsKey("roof_type_alias_conflict"))) {
                return conflictGap();
            }
            // Sealed dual aliases with different values (pre-merge components).
            if (com.formacraft.common.typology.UserConstraintContract
                    .isServerVerified(c.params(), "roofType")
                    && com.formacraft.common.typology.UserConstraintContract
                    .isServerVerified(c.params(), "roof_type")) {
                Object a = c.params().get("roofType");
                Object b = c.params().get("roof_type");
                if (a != null && b != null
                        && !normalizeRoof(a).equals(normalizeRoof(b))) {
                    return conflictGap();
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<CapabilityGap> conflictGap() {
        return Optional.of(new CapabilityGap(
                "E_ROOF_ALIAS_CONFLICT",
                "可信屋顶别名冲突：roofType 与 roof_type 均为 USER_EXPLICIT 且取值不同",
                "components[].params",
                List.of("Use a single explicit roof form", "Clarify roofType vs roof_type")));
    }

    private static boolean truthy(Object v) {
        if (v instanceof Boolean b) return b;
        if (v == null) return false;
        String s = String.valueOf(v).trim().toLowerCase();
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }

    private static String normalizeRoof(Object v) {
        String s = String.valueOf(v).trim().toLowerCase();
        return switch (s) {
            case "xieshan", "xie_shan", "歇山" -> "xie_shan";
            case "xuanshan", "xuan_shan", "悬山" -> "xuan_shan";
            case "yingshan", "ying_shan", "硬山" -> "ying_shan";
            case "wudian", "wu_dian", "庑殿" -> "wu_dian";
            default -> s;
        };
    }
}
