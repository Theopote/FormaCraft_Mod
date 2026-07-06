package com.formacraft.common.llm;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.StyleAttributes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Propagates top-level {@link LlmPlan#distinguishingFeatures()} into fields the Java
 * generation pipeline already consumes ({@link StyleAttributes#decorativeElements()},
 * {@link Component#features()}).
 */
public final class DistinguishingFeaturesBridge {

    private static final int MAX_FEATURES = 12;
    private static final Set<String> TARGET_COMPONENT_TYPES = Set.of(
            "MASS_MAIN",
            "MASS_SECONDARY",
            "ROOF",
            "STRUCTURE",
            "CROWN"
    );

    private DistinguishingFeaturesBridge() {}

    public static LlmPlan enrich(LlmPlan plan) {
        if (plan == null) {
            return null;
        }
        List<String> distinguishing = normalizeTokens(plan.distinguishingFeatures());
        if (distinguishing.isEmpty()) {
            return plan;
        }

        StyleAttributes styleAttributes = mergeStyleAttributes(plan.styleAttributes(), distinguishing);
        List<Component> components = enrichComponents(plan.components(), distinguishing);

        return new LlmPlan(
                plan.mode(),
                plan.styleProfile(),
                plan.anchor(),
                plan.globalConstraints(),
                plan.layout(),
                components,
                plan.genome(),
                styleAttributes,
                plan.proportionHints(),
                plan.alignmentAndSymmetry(),
                plan.targetSlotId(),
                plan.allowedArea(),
                plan.patch(),
                plan.planProgram(),
                plan.planSkeleton(),
                plan.planStatus(),
                plan.error(),
                plan.capabilityGap(),
                plan.playerFidelityNoticeZh(),
                plan.distinguishingFeatures()
        );
    }

    static List<String> normalizeTokens(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String item : raw) {
            if (item == null) {
                continue;
            }
            String trimmed = item.trim();
            if (!trimmed.isEmpty() && !containsIgnoreCase(out, trimmed)) {
                out.add(trimmed);
            }
        }
        return out.size() > MAX_FEATURES ? out.subList(0, MAX_FEATURES) : out;
    }

    static String slugify(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        String slug = token.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        slug = slug.replaceAll("^_+", "").replaceAll("_+$", "");
        return slug;
    }

    private static StyleAttributes mergeStyleAttributes(StyleAttributes attrs, List<String> distinguishing) {
        List<String> mergedDecorative = new ArrayList<>();
        if (attrs != null && attrs.decorativeElements() != null) {
            for (String existing : attrs.decorativeElements()) {
                if (existing != null && !existing.isBlank() && !containsIgnoreCase(mergedDecorative, existing)) {
                    mergedDecorative.add(existing);
                }
            }
        }
        for (String token : distinguishing) {
            if (!containsIgnoreCase(mergedDecorative, token)) {
                mergedDecorative.add(token);
            }
            if (mergedDecorative.size() >= MAX_FEATURES) {
                break;
            }
        }

        if (attrs == null) {
            return new StyleAttributes(null, null, null, null, null, null, mergedDecorative, null);
        }
        return new StyleAttributes(
                attrs.wallColor(),
                attrs.wallMaterial(),
                attrs.roofColor(),
                attrs.roofMaterial(),
                attrs.accentMaterial(),
                attrs.floorMaterial(),
                mergedDecorative,
                attrs.customAttributes()
        );
    }

    private static List<Component> enrichComponents(List<Component> components, List<String> distinguishing) {
        if (components == null || components.isEmpty()) {
            return components;
        }
        List<Component> out = new ArrayList<>(components.size());
        for (Component component : components) {
            if (component == null) {
                continue;
            }
            String type = component.componentType();
            if (type == null || !TARGET_COMPONENT_TYPES.contains(type.toUpperCase(Locale.ROOT))) {
                out.add(component);
                continue;
            }

            List<String> features = new ArrayList<>();
            if (component.features() != null) {
                features.addAll(component.features());
            }
            for (String token : distinguishing) {
                String slug = slugify(token);
                if (!slug.isEmpty() && !featureContains(features, slug)) {
                    features.add(slug);
                }
            }

            Map<String, Object> params = component.params() != null
                    ? new HashMap<>(component.params())
                    : new HashMap<>();
            if (!params.containsKey("distinctive_features") && !distinguishing.isEmpty()) {
                params.put("distinctive_features", List.copyOf(distinguishing));
            }

            out.add(new Component(
                    component.componentType(),
                    component.slotId(),
                    component.relativePosition(),
                    component.dimensions(),
                    features,
                    params
            ));
        }
        return out;
    }

    private static boolean containsIgnoreCase(List<String> values, String candidate) {
        if (candidate == null) {
            return false;
        }
        String needle = candidate.toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).equals(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean featureContains(List<String> features, String slug) {
        String needle = slug.toLowerCase(Locale.ROOT);
        for (String feature : features) {
            if (feature != null && feature.toLowerCase(Locale.ROOT).equals(needle)) {
                return true;
            }
        }
        return false;
    }
}
