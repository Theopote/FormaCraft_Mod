package com.formacraft.server.generation.component.adaptor;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.ComponentGeneratorRegistry;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.skeleton.ExecutableSkeletonPlan;
import com.formacraft.common.skeleton.SkeletonExecutors;
import com.formacraft.server.generation.component.impl.AssemblyPatchGenerator;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Hint-only checks for routing; must not generate BlockPatch. */
final class ComponentRouteHints {
    private static final double ARCHETYPE_STRONG_THRESHOLD = 0.85;
    private static final Set<String> WHOLE_BUILDING_TYPES = Set.of(
            "HOUSE", "CASTLE", "COMPOUND", "LANDMARK", "BUILDING"
    );

    private ComponentRouteHints() {}

    static boolean acceptsStair(Component c) {
        return com.formacraft.server.generation.component.impl.StraightStairComponentGenerator.accepts(c);
    }

    static boolean canRunSkeleton(SemanticComponent semantic, ServerWorld world) {
        if (world == null || !SkeletonExecutors.isRegistered()) {
            return false;
        }
        if (parseSkeletonFromComponent(semantic.source()) == null) {
            return false;
        }
        return resolveSlotAnchor(semantic) != null;
    }

    static boolean canRunAssembly(SemanticComponent semantic, ServerWorld world) {
        return world != null && AssemblyPatchGenerator.isRoutableAssembly(semantic);
    }

    static boolean hasExplicitLibraryRequest(Component c) {
        return hasFeaturePrefix(c, "group_request:") || hasFeaturePrefix(c, "component_request:");
    }

    static boolean hasTypologyHint(Component c) {
        return com.formacraft.common.typology.TypologyComponentRouter.hasTypologyHint(c);
    }

    static boolean structureFallbackEligible(SemanticComponent semantic) {
        return UnifiedGeneratorRouter.shouldUseStructureFallbackForRouting(semantic);
    }

    static boolean hasRegisteredGenerator(String componentType) {
        return ComponentGeneratorRegistry.getGenerator(componentType) != null;
    }

    static ExecutableSkeletonPlan parseSkeletonFromComponent(Component c) {
        if (c == null) {
            return null;
        }
        Map<String, Object> params = c.params();
        if (params != null) {
            Object sk = params.get("skeleton");
            if (sk instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                ExecutableSkeletonPlan plan = ExecutableSkeletonPlan.fromMap((Map<String, Object>) map);
                if (plan != null) {
                    return plan;
                }
            }
        }
        String json = extractFeaturePayload(c, "skeleton:");
        if (json != null && !json.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = JsonUtil.fromJson(json, Map.class);
                return ExecutableSkeletonPlan.fromMap(map);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static SlotAnchor resolveSlotAnchor(SemanticComponent semantic) {
        if (semantic.slot() == null || semantic.slot().anchor() == null) {
            return null;
        }
        var a = semantic.slot().anchor();
        return new SlotAnchor(new BlockPos(a.x(), a.y(), a.z()));
    }

    private record SlotAnchor(BlockPos worldPos) {}

    private static String extractFeaturePayload(Component component, String prefix) {
        if (component == null || prefix == null) {
            return null;
        }
        List<String> features = component.features();
        if (features == null || features.isEmpty()) {
            return null;
        }
        String p = prefix.toLowerCase(Locale.ROOT);
        for (String feature : features) {
            if (feature == null) continue;
            String lower = feature.toLowerCase(Locale.ROOT);
            if (lower.startsWith(p)) {
                return feature.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private static boolean hasFeaturePrefix(Component component, String prefix) {
        if (component == null || prefix == null) return false;
        List<String> features = component.features();
        if (features == null || features.isEmpty()) return false;
        String p = prefix.toLowerCase(Locale.ROOT);
        for (String feature : features) {
            if (feature == null) continue;
            if (feature.toLowerCase(Locale.ROOT).startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    static double archetypeStrongThreshold() {
        return ARCHETYPE_STRONG_THRESHOLD;
    }

    static Set<String> wholeBuildingTypes() {
        return WHOLE_BUILDING_TYPES;
    }
}
