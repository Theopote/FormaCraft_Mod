package com.formacraft.server.assembly;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.Locale;

/** Helpers for ASSEMBLY-only plans and explicit capability gaps. */
public final class AssemblyPlanCapability {

    private AssemblyPlanCapability() {}

    public static boolean isAssemblyOnly(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return false;
        }
        boolean hasAssembly = false;
        for (Component c : plan.components()) {
            if (c == null) {
                continue;
            }
            String type = normalizeType(c.componentType());
            if ("ASSEMBLY".equals(type)) {
                hasAssembly = true;
                continue;
            }
            if (!isDecorativeOrEmpty(type)) {
                return false;
            }
        }
        return hasAssembly;
    }

    private static boolean isDecorativeOrEmpty(String type) {
        if (type == null || type.isBlank()) {
            return true;
        }
        return switch (type) {
            case "PATH", "ROAD", "PAVING", "PLAZA", "GARDEN", "TERRAIN", "GROUND" -> true;
            default -> false;
        };
    }

    private static String normalizeType(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
