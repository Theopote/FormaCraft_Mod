package com.formacraft.common.generation.component.util;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.Locale;
import java.util.Map;

/**
 * 冠部装配：解析 crown_template、尺寸与启用条件。
 */
public final class ComponentCrownDecorator {

    private ComponentCrownDecorator() {}

    public static boolean shouldApply(LlmPlan plan, Map<String, Object> massParams) {
        if (isDisabled(getParam(massParams, "crown_assembly", "crownAssembly", "crown"))) {
            return false;
        }
        if (plan != null && plan.proportionHints() != null) {
            Map<String, Object> hints = plan.proportionHints();
            if (isDisabled(hints.get("crown_assembly")) || isDisabled(hints.get("crownAssembly"))) {
                return false;
            }
            if (isEnabled(hints.get("crown_assembly")) || isEnabled(hints.get("crownAssembly"))) {
                return true;
            }
            if (hasTemplateHint(hints)) {
                return true;
            }
            String typology = String.valueOf(hints.getOrDefault("typology", "")).toLowerCase(Locale.ROOT);
            if (typology.contains("monument") || typology.contains("classical")
                    || typology.contains("palace") || typology.contains("cathedral")
                    || typology.contains("baroque") || typology.contains("castle")) {
                return true;
            }
        }
        if (hasTemplateHint(massParams)) {
            return true;
        }
        String profile = getParam(massParams, "facade_profile", "facadeProfile");
        if (profile != null) {
            String fp = profile.toLowerCase(Locale.ROOT);
            return fp.contains("pilaster") || fp.contains("colonnade") || fp.contains("classical");
        }
        return false;
    }

    public static String resolveTemplate(LlmPlan plan, Map<String, Object> params, SemanticComponent semantic) {
        String raw = getParam(params, "crown_template", "crownTemplate", "crown_type", "crownType");
        if (raw != null) {
            return CrownTemplateLibrary.normalize(raw);
        }
        if (plan != null && plan.proportionHints() != null) {
            Object hint = plan.proportionHints().get("crown_template");
            if (hint == null) {
                hint = plan.proportionHints().get("crownTemplate");
            }
            if (hint != null && !String.valueOf(hint).isBlank()) {
                return CrownTemplateLibrary.normalize(String.valueOf(hint));
            }
            String typology = String.valueOf(plan.proportionHints().getOrDefault("typology", "")).toLowerCase(Locale.ROOT);
            if (typology.contains("baroque") || typology.contains("orthodox") || typology.contains("onion")) {
                return CrownTemplateLibrary.ONION_DOME;
            }
            if (typology.contains("classical") || typology.contains("monument") || typology.contains("palace")) {
                return CrownTemplateLibrary.CLASSICAL_CUPOLA;
            }
        }
        if (semantic != null && semantic.source() != null && semantic.source().features() != null) {
            for (String f : semantic.source().features()) {
                if (f == null) {
                    continue;
                }
                String lower = f.toLowerCase(Locale.ROOT);
                if (lower.contains("onion") || lower.contains("baroque")) {
                    return CrownTemplateLibrary.ONION_DOME;
                }
                if (lower.contains("cupola") || lower.contains("crown") || lower.contains("dome")) {
                    return CrownTemplateLibrary.CLASSICAL_CUPOLA;
                }
            }
        }
        String style = plan != null && plan.styleProfile() != null ? plan.styleProfile().toUpperCase(Locale.ROOT) : "";
        if (style.contains("GOTHIC") || style.contains("BAROQUE") || style.contains("ORTHODOX")) {
            return CrownTemplateLibrary.ONION_DOME;
        }
        if (style.contains("CLASSICAL") || style.contains("MONUMENT") || style.contains("FRENCH")) {
            return CrownTemplateLibrary.CLASSICAL_CUPOLA;
        }
        return CrownTemplateLibrary.SIMPLE_DOME;
    }

    public static int resolveSegments(Map<String, Object> params) {
        int seg = ComponentParamParsers.intParam(params, 32, "revolve_segments", "revolveSegments", "segments");
        return Math.max(8, Math.min(seg, 64));
    }

    /**
     * Explicit revolve profile from params, or null to fall back to {@link CrownTemplateLibrary}.
     */
    public static List<double[]> resolveExplicitProfile(Map<String, Object> params) {
        return RevolveProfileParser.resolve(params);
    }

    public record CrownDimensions(int width, int depth, int height, double radiusScale) {}

    public static CrownDimensions resolveDimensions(Dimensions dims, Map<String, Object> params) {
        int span = 9;
        if (dims != null) {
            span = Math.max(dims.width(), dims.depth());
        }
        int radius = ComponentParamParsers.intParam(params, 0, "crown_radius", "crownRadius", "radius");
        if (radius <= 0) {
            radius = Math.max(2, Math.min(6, span / 4));
        }
        int height = ComponentParamParsers.intParam(params, 0, "crown_height", "crownHeight");
        if (height <= 0) {
            height = Math.max(4, Math.min(10, radius * 2));
        }
        int width = radius * 2 + 1;
        int depth = radius * 2 + 1;
        return new CrownDimensions(width, depth, height, radius);
    }

    private static boolean hasTemplateHint(Map<String, Object> params) {
        if (params == null) {
            return false;
        }
        return getParam(params, "crown_template", "crownTemplate", "crown_type", "crownType") != null;
    }

    private static boolean isDisabled(Object v) {
        if (v == null) {
            return false;
        }
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return "false".equals(s) || "off".equals(s) || "none".equals(s);
    }

    private static boolean isEnabled(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return "true".equals(s) || "yes".equals(s) || "on".equals(s);
    }

    private static String getParam(Map<String, Object> params, String... keys) {
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
}
