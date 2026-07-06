package com.formacraft.common.typology;

import com.formacraft.FormacraftMod;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.server.world.ServerWorld;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Routes {@code STRUCTURE} / typology-feature components to {@link TypologyInterpreterRegistry}.
 */
public final class TypologyComponentRouter {

    private TypologyComponentRouter() {}

    public static List<BlockPatch> tryGenerate(SemanticComponent semantic, ServerWorld world) {
        if (semantic == null || world == null || semantic.source() == null) {
            return List.of();
        }

        String typologyId = extractTypologyId(semantic.source());
        if (typologyId == null || typologyId.isBlank()) {
            return List.of();
        }

        TypologyInterpreter interpreter = TypologyInterpreterRegistry.get(typologyId);
        if (interpreter == null) {
            FormacraftMod.LOGGER.debug("TypologyComponentRouter: no interpreter registered for {}", typologyId);
            return List.of();
        }

        try {
            List<BlockPatch> patches = interpreter.interpret(semantic, world);
            if (patches != null && !patches.isEmpty()) {
                com.formacraft.common.network.metrics.TypologyRoutingMetrics.recordTypologyComponentHit(typologyId);
                FormacraftMod.LOGGER.debug(
                        "TypologyComponentRouter: typology={} produced {} patches",
                        typologyId, patches.size()
                );
                return patches;
            }
        } catch (Exception e) {
            FormacraftMod.LOGGER.warn("TypologyComponentRouter: interpreter failed for {}", typologyId, e);
        }
        return List.of();
    }

    public static boolean hasTypologyHint(Component component) {
        return extractTypologyId(component) != null;
    }

    public static String extractTypologyId(Component component) {
        if (component == null) {
            return null;
        }

        String fromFeature = extractFeaturePayload(component, "typology:");
        if (fromFeature != null && !fromFeature.isBlank()) {
            return fromFeature.trim();
        }

        Map<String, Object> params = component.params();
        if (params != null) {
            for (String key : List.of("typology_id", "structural_typology", "typologyId", "structuralTypologyId")) {
                Object v = params.get(key);
                if (v != null) {
                    String s = String.valueOf(v).trim();
                    if (!s.isEmpty()) {
                        return s;
                    }
                }
            }
        }

        String legacyModule = extractLandmarkModuleId(component);
        if (legacyModule != null) {
            String migrated = StructuralTypologyRegistry.typologyForLegacyModule(legacyModule);
            if (migrated != null && !migrated.isBlank()) {
                return migrated;
            }
        }
        return null;
    }

    public static String extractLandmarkModuleId(Component component) {
        if (component == null) {
            return null;
        }
        String fromFeature = extractFeaturePayload(component, "landmark:");
        if (fromFeature != null && !fromFeature.isBlank()) {
            return fromFeature.trim();
        }
        fromFeature = extractFeaturePayload(component, "module:");
        if (fromFeature != null && !fromFeature.isBlank()) {
            return fromFeature.trim();
        }
        Map<String, Object> params = component.params();
        if (params != null && params.get("module_id") != null) {
            String s = String.valueOf(params.get("module_id")).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    public static String extractReferenceLandmark(Component component) {
        if (component == null) {
            return null;
        }
        Map<String, Object> params = component.params();
        if (params != null) {
            for (String key : List.of("reference_landmark", "referenceLandmarkId", "referenceLandmark")) {
                Object v = params.get(key);
                if (v != null) {
                    String s = String.valueOf(v).trim();
                    if (!s.isEmpty()) {
                        return s;
                    }
                }
            }
        }
        String legacyModule = extractLandmarkModuleId(component);
        if (legacyModule != null && StructuralTypologyRegistry.isDeprecatedLegacyModule(legacyModule)) {
            return legacyModule;
        }
        return null;
    }

    /**
     * Resolve typology id from a deprecated landmark module id via migration map.
     */
    public static String typologyForLegacyLandmark(String legacyModuleId) {
        return StructuralTypologyRegistry.typologyForLegacyModule(legacyModuleId);
    }

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
            if (feature == null) {
                continue;
            }
            String lower = feature.toLowerCase(Locale.ROOT);
            if (lower.startsWith(p)) {
                return feature.substring(prefix.length()).trim();
            }
        }
        return null;
    }
}
