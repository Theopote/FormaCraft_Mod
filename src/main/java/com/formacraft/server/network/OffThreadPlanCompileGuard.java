package com.formacraft.server.network;

import com.formacraft.common.generation.routing.GenerationAuthority;
import com.formacraft.common.generation.routing.PlanMetaAccess;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.typology.TypologyComponentRouter;

import java.util.Locale;

/**
 * Decides whether {@code ComponentPlanCompiler.compile(..., world=null, ...)} is safe.
 * Paths that need {@link net.minecraft.server.world.ServerWorld} (library, typology,
 * assembly, skeleton) must stay on the server thread with a live world.
 */
public final class OffThreadPlanCompileGuard {
    private OffThreadPlanCompileGuard() {}

    public static boolean allowsNullWorldCompile(LlmPlan plan) {
        return plan != null && plan.usesComponentMode() && !requiresLiveWorld(plan);
    }

    /** True when null-world compile would skip, fail, or silently degrade. */
    public static boolean requiresLiveWorld(LlmPlan plan) {
        if (plan == null) {
            return true;
        }
        GenerationAuthority planAuth = PlanMetaAccess.planGenerationAuthority(plan);
        if (isWorldBoundAuthority(planAuth)) {
            return true;
        }
        if (plan.components() == null) {
            return false;
        }
        for (Component c : plan.components()) {
            if (c == null) {
                continue;
            }
            GenerationAuthority auth = GenerationAuthority.resolve(c, plan.planMeta());
            if (isWorldBoundAuthority(auth)) {
                return true;
            }
            String type = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
            if ("ASSEMBLY".equals(type) || "STRUCTURE".equals(type) || "MODULE".equals(type)
                    || type.startsWith("SKELETON")) {
                return true;
            }
            if (TypologyComponentRouter.hasTypologyHint(c)) {
                return true;
            }
            if (TypologyComponentRouter.extractLandmarkModuleId(c) != null) {
                return true;
            }
            if (hasWorldBoundFeature(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWorldBoundAuthority(GenerationAuthority auth) {
        return auth == GenerationAuthority.ASSEMBLY
                || auth == GenerationAuthority.SKELETON
                || auth == GenerationAuthority.CAPTURED_LIBRARY
                || auth == GenerationAuthority.TYPOLOGY;
    }

    private static boolean hasWorldBoundFeature(Component c) {
        if (c.features() == null) {
            return false;
        }
        for (String feature : c.features()) {
            if (feature == null || feature.isBlank()) {
                continue;
            }
            String lower = feature.trim().toLowerCase(Locale.ROOT);
            if (lower.startsWith("component_request:")
                    || lower.startsWith("group_request:")
                    || lower.startsWith("typology:")
                    || lower.startsWith("landmark:")
                    || lower.startsWith("module:")
                    || lower.startsWith("skeleton:")) {
                return true;
            }
        }
        return false;
    }
}
