package com.formacraft.common.llm;

import com.formacraft.FormacraftMod;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Java-side hard guard for open-world / organic / parametric buildings (Sydney Opera, Zaha, etc.).
 * <p>
 * Python sets {@link LlmPlan#enrichmentGuard()} when classical pilaster/cornice/crown enrichment
 * was skipped. This class strips classical residue from the LLM plan and blocks compiler-inferred
 * CROWN components that would re-introduce monument grammar.
 */
public final class NonClassicalEnrichmentGuard {

    private static final Set<String> CROWN_COMPONENT_TYPES = Set.of("CROWN", "CUPOLA", "DOME");
    private static final Set<String> CLASSICAL_TYPOLOGY_HINTS = Set.of(
            "stadium_bowl", "classical_monument", "baroque", "palace", "cathedral", "castle"
    );

    private static final List<String> PLAN_NON_CLASSICAL_MARKERS = List.of(
            "zaha", "hadid", "gehry", "deconstruct", "parametric", "organic", "freeform",
            "curvilinear", "shell structure", "sydney opera", "opera house", "bird's nest",
            "birds nest", "television tower", "tv tower", "observation tower", "observation sphere",
            "oriental pearl", "东方明珠", "电视塔", "united nations", "un headquarters",
            "联合国", "egyptian pyramid", "埃及金字塔", "金字塔"
    );

    private static final List<String> CLASSICAL_CONTEXT_MARKERS = List.of(
            "pantheon", "parthenon", "classical", "neoclassical", "baroque", "gothic cathedral",
            "roman temple", "capitol", "medieval_castle", "万神殿"
    );

    private NonClassicalEnrichmentGuard() {}

    public static boolean isActive(LlmPlan plan) {
        if (plan == null) {
            return false;
        }
        String guard = plan.enrichmentGuard();
        if (guard != null && !guard.isBlank()) {
            String normalized = guard.trim().toLowerCase(Locale.ROOT);
            return normalized.startsWith("non_classical_marker:")
                    || normalized.startsWith("archetype:")
                    || normalized.equals("distinguishing_features_non_classical")
                    || normalized.startsWith("footprint:");
        }
        return inferNonClassicalFromPlan(plan);
    }

    public static boolean blocksCrownInference(LlmPlan plan) {
        return isActive(plan);
    }

    /**
     * Strip classical params/components that leak through LLM output or proportion cards.
     */
    public static LlmPlan sanitize(LlmPlan plan) {
        if (!isActive(plan)) {
            return plan;
        }

        Map<String, Object> proportionHints = sanitizeProportionHints(plan.proportionHints());
        List<Component> components = sanitizeComponents(plan.components());
        int strippedCrowns = countRemovedCrowns(plan.components(), components);

        if (strippedCrowns > 0 || proportionHints != plan.proportionHints() || components != plan.components()) {
            FormacraftMod.LOGGER.info(
                    "NonClassicalEnrichmentGuard: active guard={} stripped_crown_components={}",
                    plan.enrichmentGuard(),
                    strippedCrowns
            );
        }

        return new LlmPlan(
                plan.mode(),
                plan.styleProfile(),
                plan.anchor(),
                plan.globalConstraints(),
                plan.layout(),
                components,
                plan.genome(),
                plan.styleAttributes(),
                proportionHints,
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
                plan.distinguishingFeatures(),
                plan.enrichmentGuard()
        );
    }

    private static int countRemovedCrowns(List<Component> before, List<Component> after) {
        if (before == null || after == null) {
            return 0;
        }
        return Math.max(0, before.size() - after.size());
    }

    private static Map<String, Object> sanitizeProportionHints(Map<String, Object> hints) {
        if (hints == null || hints.isEmpty()) {
            return hints;
        }
        Map<String, Object> out = new HashMap<>(hints);
        Object typology = out.get("typology");
        if (typology != null && isClassicalTypology(String.valueOf(typology))) {
            out.remove("typology");
        }
        out.put("crown_assembly", false);
        out.remove("crown_template");
        out.remove("crownTemplate");
        Object roofSpecialty = out.get("roof_specialty");
        if (roofSpecialty != null && String.valueOf(roofSpecialty).toLowerCase(Locale.ROOT).contains("mansard")) {
            out.remove("roof_specialty");
            out.remove("roof_dormers");
        }
        return out;
    }

    private static boolean isClassicalTypology(String typology) {
        String lower = typology.toLowerCase(Locale.ROOT);
        for (String hint : CLASSICAL_TYPOLOGY_HINTS) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static List<Component> sanitizeComponents(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return components;
        }
        List<Component> out = new ArrayList<>(components.size());
        for (Component component : components) {
            if (component == null) {
                continue;
            }
            String type = normalizeType(component.componentType());
            if (CROWN_COMPONENT_TYPES.contains(type)) {
                continue;
            }
            Map<String, Object> params = component.params() != null
                    ? new HashMap<>(component.params())
                    : new HashMap<>();
            List<String> features = component.features() != null
                    ? new ArrayList<>(component.features())
                    : new ArrayList<>();

            if (isMassType(type)) {
                stripClassicalMassParams(params);
                stripClassicalFeatures(features);
            } else if ("ROOF".equals(type) || "ROOF_STRUCTURE".equals(type)) {
                stripClassicalRoofParams(params);
            } else if ("DECOR_DETAIL".equals(type)) {
                stripClassicalFeatures(features);
                stripClassicalDecorParams(params);
            } else if ("FACADE_WINDOWS".equals(type)) {
                stripClassicalFacadeParams(params);
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

    private static void stripClassicalMassParams(Map<String, Object> params) {
        String facade = paramString(params, "facade_profile", "facadeProfile");
        if (facade != null && isClassicalFacade(facade)) {
            params.put("facade_profile", "none");
        }
        params.remove("floor_cornice");
        String roofType = paramString(params, "roof_type", "roofType");
        if (roofType != null && roofType.toLowerCase(Locale.ROOT).contains("mansard")) {
            params.put("roof_type", "flat");
        }
    }

    private static void stripClassicalRoofParams(Map<String, Object> params) {
        if (hasRevolvedSurface(params)) {
            return;
        }
        String roofType = paramString(params, "roof_type", "roofType");
        if (roofType != null) {
            String lower = roofType.toLowerCase(Locale.ROOT);
            if (lower.contains("mansard")) {
                params.put("roof_type", "hip");
            } else if (lower.contains("gable") || lower.contains("hip")) {
                params.put("roof_type", "flat");
            }
        }
        String template = paramString(params, "crown_template", "crownTemplate");
        if (template != null && isClassicalCrownTemplate(template)) {
            params.remove("crown_template");
            params.remove("crownTemplate");
        }
        params.remove("roof_dormers");
        params.remove("roof_specialty");
    }

    private static boolean inferNonClassicalFromPlan(LlmPlan plan) {
        String blob = planTextBlob(plan);
        if (blob.isBlank() || hasClassicalContext(blob)) {
            return false;
        }
        for (String marker : PLAN_NON_CLASSICAL_MARKERS) {
            if (blob.contains(marker.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        if (blob.contains("\"id\":\"pyramid\"") || blob.contains("archetype_id=pyramid")) {
            return true;
        }
        return blob.contains("observation_sphere") || blob.contains("tower_body_upper");
    }

    private static boolean hasClassicalContext(String blob) {
        for (String marker : CLASSICAL_CONTEXT_MARKERS) {
            if (blob.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String planTextBlob(LlmPlan plan) {
        StringBuilder sb = new StringBuilder();
        if (plan.styleProfile() != null) {
            sb.append(plan.styleProfile()).append(' ');
        }
        if (plan.distinguishingFeatures() != null) {
            for (String feature : plan.distinguishingFeatures()) {
                if (feature != null) {
                    sb.append(feature).append(' ');
                }
            }
        }
        if (plan.genome() != null) {
            sb.append(JsonUtil.toJson(plan.genome())).append(' ');
        }
        if (plan.components() != null) {
            for (Component component : plan.components()) {
                if (component == null) {
                    continue;
                }
                if (component.componentType() != null) {
                    sb.append(component.componentType()).append(' ');
                }
                if (component.features() != null) {
                    for (String feature : component.features()) {
                        if (feature != null) {
                            sb.append(feature).append(' ');
                        }
                    }
                }
                if (component.params() != null) {
                    sb.append(JsonUtil.toJson(component.params())).append(' ');
                }
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static void stripClassicalFacadeParams(Map<String, Object> params) {
        String rhythm = paramString(params, "rhythm_preset", "rhythmPreset");
        if (rhythm != null && rhythm.toUpperCase(Locale.ROOT).contains("PILASTER")) {
            params.remove("rhythm_preset");
            params.remove("rhythmPreset");
        }
        Object pattern = params.get("repeating_pattern");
        if (pattern instanceof Map<?, ?> repeating) {
            Object elements = repeating.get("elements");
            if (elements instanceof List<?> list && containsPillarElement(list)) {
                params.remove("repeating_pattern");
            }
        }
    }

    private static void stripClassicalDecorParams(Map<String, Object> params) {
        String cutout = paramString(params, "facade_cutout", "facadeCutout");
        if (cutout != null && !"none".equalsIgnoreCase(cutout)) {
            params.put("facade_cutout", "none");
        }
    }

    private static void stripClassicalFeatures(List<String> features) {
        features.removeIf(NonClassicalEnrichmentGuard::isClassicalFeatureToken);
    }

    private static boolean isClassicalFeatureToken(String feature) {
        if (feature == null || feature.isBlank()) {
            return false;
        }
        String lower = feature.toLowerCase(Locale.ROOT);
        return lower.contains("pilaster")
                || lower.contains("colonnade")
                || lower.contains("cornice")
                || lower.contains("mansard")
                || lower.contains("cupola")
                || (lower.contains("crown") && !lower.contains("shell"));
    }

    private static boolean isClassicalFacade(String facade) {
        String lower = facade.toLowerCase(Locale.ROOT);
        return lower.contains("pilaster")
                || lower.contains("colonnade")
                || lower.contains("classical")
                || lower.contains("mullion_grid");
    }

    private static boolean isClassicalCrownTemplate(String template) {
        String upper = template.toUpperCase(Locale.ROOT);
        return upper.contains("CLASSICAL") || upper.contains("CUPOLA") || upper.contains("ONION");
    }

    private static boolean hasRevolvedSurface(Map<String, Object> params) {
        String method = paramString(params, "generation_method", "generationMethod");
        return method != null && method.toLowerCase(Locale.ROOT).contains("revolved");
    }

    private static boolean containsPillarElement(List<?> elements) {
        for (Object element : elements) {
            if (element instanceof Map<?, ?> map) {
                Object type = map.get("type");
                if (type != null && "pillar".equalsIgnoreCase(String.valueOf(type))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isMassType(String type) {
        return "MASS_MAIN".equals(type) || "MASS_SECONDARY".equals(type) || "MASS_WING".equals(type);
    }

    private static String normalizeType(String type) {
        if (type == null) {
            return "";
        }
        return type.trim().toUpperCase(Locale.ROOT);
    }

    private static String paramString(Map<String, Object> params, String... keys) {
        if (params == null) {
            return null;
        }
        for (String key : keys) {
            Object value = params.get(key);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return null;
    }
}
