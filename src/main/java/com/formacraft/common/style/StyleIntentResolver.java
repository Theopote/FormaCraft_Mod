package com.formacraft.common.style;

import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.StyleAttributes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * StyleIntentResolver
 *
 * Apply style-driven defaults to components when the LLM output is missing or set to "none".
 * This helps avoid uniform rectangular plans and improves stylistic distinctiveness.
 */
public final class StyleIntentResolver {

    private StyleIntentResolver() {}

    private enum StyleFlavor {
        GOTHIC,
        HUI,
        CHINESE,
        JAPANESE,
        INDUSTRIAL,
        MODERN,
        DEFAULT
    }

    public static Component apply(LlmPlan plan, Component component) {
        if (component == null) {
            return null;
        }

        StyleFlavor flavor = detectFlavor(plan, component);
        if (flavor == StyleFlavor.DEFAULT) {
            return component;
        }

        String type = normalizeType(component.componentType());
        boolean mass = isMassType(type);
        boolean roof = isRoofType(type);
        boolean facade = "FACADE_WINDOWS".equals(type);
        if (!mass && !roof && !facade) {
            return component;
        }

        Map<String, Object> params = new HashMap<>();
        if (component.params() != null) {
            params.putAll(component.params());
        }

        List<String> features = new ArrayList<>();
        if (component.features() != null) {
            features.addAll(component.features());
        }

        boolean changed = false;

        if (mass) {
            String planType = getParamString(params, "plan_type", "planType", "footprint_pattern",
                    "footprintPattern", "plan_pattern", "planPattern");
            if (isBlankOrNone(planType)) {
                String fallback = resolvePlanType(flavor, component.dimensions());
                if (fallback != null) {
                    params.put("plan_type", fallback);
                    changed = true;
                }
            }

            Double windowRatio = ComponentParamParsers.doubleOrNull(params, "window_ratio", "windowRatio");
            if (windowRatio == null) {
                Double fallback = resolveWindowRatio(flavor);
                if (fallback != null) {
                    params.put("window_ratio", fallback);
                    changed = true;
                }
            }

            Integer wallThickness = ComponentParamParsers.intOrNull(params, "wall_thickness", "wallThickness");
            if (wallThickness == null) {
                Integer fallback = resolveWallThickness(flavor);
                if (fallback != null) {
                    params.put("wall_thickness", fallback);
                    changed = true;
                }
            }
        }

        if (mass || roof) {
            String roofType = getParamString(params, "roof_type", "roofType");
            if (isBlankOrNone(roofType)) {
                String fallback = resolveRoofType(flavor);
                if (fallback != null) {
                    params.put("roof_type", fallback);
                    changed = true;
                }
            }
        }

        if (facade) {
            String windowStyle = getParamString(params, "window_style", "windowStyle");
            if (isBlankOrNone(windowStyle)) {
                String fallback = resolveWindowStyle(flavor);
                if (fallback != null) {
                    params.put("window_style", fallback);
                    changed = true;
                }
            }
        }

        changed |= appendStyleFeatures(flavor, features);

        if (!changed) {
            return component;
        }

        return new Component(
                component.componentType(),
                component.slotId(),
                component.relativePosition(),
                component.dimensions(),
                features,
                params
        );
    }

    private static StyleFlavor detectFlavor(LlmPlan plan, Component component) {
        Set<String> tokens = new HashSet<>();

        if (plan != null) {
            String profile = plan.styleProfile();
            if (profile != null) {
                tokens.add(profile.toLowerCase(Locale.ROOT));
            }
            if (plan.genome() != null && plan.genome().culturalStyle != null) {
                if (plan.genome().culturalStyle.region != null) {
                    tokens.add(plan.genome().culturalStyle.region.toLowerCase(Locale.ROOT));
                }
                if (plan.genome().culturalStyle.keywords != null) {
                    for (String k : plan.genome().culturalStyle.keywords) {
                        if (k != null) {
                            tokens.add(k.toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
            StyleAttributes attrs = plan.styleAttributes();
            if (attrs != null && attrs.decorativeElements() != null) {
                for (String e : attrs.decorativeElements()) {
                    if (e != null) {
                        tokens.add(e.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }

        if (component != null && component.features() != null) {
            for (String f : component.features()) {
                if (f != null) {
                    tokens.add(f.toLowerCase(Locale.ROOT));
                }
            }
        }

        if (containsAny(tokens, "gothic", "cathedral", "church", "cruciform", "rose_window")) {
            return StyleFlavor.GOTHIC;
        }
        if (containsAny(tokens, "hui", "huizhou", "徽派")) {
            return StyleFlavor.HUI;
        }
        if (containsAny(tokens, "chinese", "中式", "siheyuan", "courtyard", "dougong")) {
            return StyleFlavor.CHINESE;
        }
        if (containsAny(tokens, "japanese", "shoji", "engawa")) {
            return StyleFlavor.JAPANESE;
        }
        if (containsAny(tokens, "industrial", "factory", "steel", "frame")) {
            return StyleFlavor.INDUSTRIAL;
        }
        if (containsAny(tokens, "modern", "minimal", "contemporary")) {
            return StyleFlavor.MODERN;
        }
        return StyleFlavor.DEFAULT;
    }

    private static String resolvePlanType(StyleFlavor flavor, Dimensions dimensions) {
        return switch (flavor) {
            case GOTHIC -> "cross";
            case HUI, CHINESE -> {
                if (dimensions != null && dimensions.width() >= 14 && dimensions.depth() >= 14) {
                    yield "courtyard";
                }
                yield "cut_corners";
            }
            case JAPANESE -> "l_shape";
            default -> null;
        };
    }

    private static String resolveRoofType(StyleFlavor flavor) {
        return switch (flavor) {
            case GOTHIC, JAPANESE -> "gable";
            case HUI, CHINESE -> "xieshan";
            default -> null;
        };
    }

    private static Double resolveWindowRatio(StyleFlavor flavor) {
        return switch (flavor) {
            case GOTHIC -> 0.38;
            case HUI, CHINESE -> 0.24;
            case JAPANESE -> 0.3;
            case MODERN -> 0.45;
            default -> null;
        };
    }

    private static Integer resolveWallThickness(StyleFlavor flavor) {
        return switch (flavor) {
            case GOTHIC, INDUSTRIAL -> 2;
            default -> null;
        };
    }

    private static String resolveWindowStyle(StyleFlavor flavor) {
        return switch (flavor) {
            case GOTHIC -> "stained";
            case HUI, CHINESE, JAPANESE -> "lattice";
            default -> null;
        };
    }

    private static boolean appendStyleFeatures(StyleFlavor flavor, List<String> features) {
        boolean changed = false;
        switch (flavor) {
            case GOTHIC -> {
                changed |= addFeature(features, "gothic");
                changed |= addFeature(features, "pointed_arches");
                changed |= addFeature(features, "stained_glass");
                changed |= addFeature(features, "flying_buttresses");
            }
            case HUI -> {
                changed |= addFeature(features, "hui");
                changed |= addFeature(features, "courtyard");
                changed |= addFeature(features, "lattice");
                changed |= addFeature(features, "wood_carvings");
            }
            case CHINESE -> {
                changed |= addFeature(features, "chinese");
                changed |= addFeature(features, "courtyard");
                changed |= addFeature(features, "lattice");
            }
            case JAPANESE -> {
                changed |= addFeature(features, "japanese");
                changed |= addFeature(features, "shoji");
            }
            case INDUSTRIAL -> {
                changed |= addFeature(features, "industrial");
                changed |= addFeature(features, "frame");
            }
            case MODERN -> {
                changed |= addFeature(features, "modern");
                changed |= addFeature(features, "curtainwall");
            }
            default -> {
            }
        }
        return changed;
    }

    private static boolean addFeature(List<String> features, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String f : features) {
            if (f != null && f.equalsIgnoreCase(value)) {
                return false;
            }
        }
        features.add(value);
        return true;
    }

    private static boolean containsAny(Set<String> tokens, String... needles) {
        for (String token : tokens) {
            if (token == null) continue;
            for (String needle : needles) {
                if (needle == null) continue;
                if (token.contains(needle.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isMassType(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        return type.startsWith("MASS")
                || type.endsWith("_MASS")
                || "SIDE_WING".equals(type)
                || "MASS_WING".equals(type)
                || "MAIN_MASS".equals(type);
    }

    private static boolean isRoofType(String type) {
        return type != null && type.startsWith("ROOF");
    }

    private static String normalizeType(String value) {
        if (value == null) return "";
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isBlankOrNone(String value) {
        if (value == null) return true;
        String v = value.trim();
        return v.isEmpty() || v.equalsIgnoreCase("none") || v.equalsIgnoreCase("default");
    }

    private static String getParamString(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) return null;
        for (String key : keys) {
            Object v = params.get(key);
            if (v == null) continue;
            String s = v.toString().trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }
}
