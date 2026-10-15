package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Canonical roof_type strings accepted when explicitly planned. */
public final class RoofTypeCatalog {
    private RoofTypeCatalog() {}

    public static final Set<String> SUPPORTED_TOKENS = Set.of(
            "flat",
            "gable", "gabled",
            "yingshan", "ying_shan", "ying-shan", "hard_gable", "hard gable", "硬山",
            "double_gable", "doublegable", "shuangpo", "双坡",
            "hip", "hipped",
            "xieshan", "xie_shan", "xie-shan", "hip_and_gable", "hip-gable", "歇山",
            "xuanshan", "xuan_shan", "xuan-shan", "overhang_gable", "悬山",
            "pyramid",
            "cone", "conical",
            "dome", "domed", "curved",
            "mansard", "mansard_roof", "baroque_roof",
            "sawtooth", "saw_tooth", "northlight", "锯齿形", "锯齿",
            "none"
    );

    public static Optional<RoofTypeKind> parseExplicit(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("none".equals(normalized)) {
            return Optional.empty();
        }
        if (!SUPPORTED_TOKENS.contains(normalized)) {
            return Optional.empty();
        }
        return Optional.of(mapToken(normalized));
    }

    public static Optional<String> extractExplicitParamType(Map<String, Object> params) {
        if (params == null) {
            return Optional.empty();
        }
        for (String key : ComponentParamParsers.keysFor("roof_type")) {
            Object raw = params.get(key);
            if (raw == null) {
                continue;
            }
            String value = String.valueOf(raw).trim();
            if (!value.isEmpty()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    public static Optional<String> extractExplicitFeatureType(Component component) {
        if (component == null || component.features() == null) {
            return Optional.empty();
        }
        for (String feature : component.features()) {
            if (feature == null) {
                continue;
            }
            String lower = feature.toLowerCase(Locale.ROOT);
            if (lower.contains("yingshan") || lower.contains("ying_shan") || lower.contains("ying-shan")
                    || lower.contains("hard_gable") || lower.contains("hard gable") || lower.contains("硬山")) {
                return Optional.of("yingshan");
            }
            if (lower.contains("xuanshan") || lower.contains("xuan_shan") || lower.contains("悬山")) {
                return Optional.of("xuanshan");
            }
            if (lower.contains("xieshan") || lower.contains("xie_shan") || lower.contains("hip_and_gable")
                    || lower.contains("歇山")) {
                return Optional.of("xieshan");
            }
            if (lower.contains("double_gable") || lower.contains("double gable") || lower.contains("shuangpo")
                    || lower.contains("双坡")) {
                return Optional.of("double_gable");
            }
            if (lower.contains("gable")) {
                return Optional.of("gable");
            }
            if (lower.contains("hip")) {
                return Optional.of("hip");
            }
            if (lower.contains("pyramid")) {
                return Optional.of("pyramid");
            }
            if (lower.contains("cone")) {
                return Optional.of("cone");
            }
            if (lower.contains("dome") || lower.contains("curved")) {
                return Optional.of("dome");
            }
            if (lower.contains("mansard") || lower.contains("baroque") || lower.contains("french")) {
                return Optional.of("mansard");
            }
            if (lower.contains("sloped") || lower.contains("pitched")) {
                return Optional.of("gable");
            }
            if (lower.contains("flat")) {
                return Optional.of("flat");
            }
        }
        return Optional.empty();
    }

    /** Explicit planned type from params or features only (not style/genome defaults). */
    public static Optional<String> extractExplicitType(Component component, Map<String, Object> params) {
        Optional<String> fromParams = extractExplicitParamType(params);
        if (fromParams.isPresent()) {
            return fromParams;
        }
        return extractExplicitFeatureType(component);
    }

    public static boolean isSupportedExplicit(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("none".equals(normalized)) {
            return true;
        }
        return SUPPORTED_TOKENS.contains(normalized);
    }

    private static RoofTypeKind mapToken(String normalized) {
        return switch (normalized) {
            case "gable", "gabled", "yingshan", "ying_shan", "ying-shan", "hard_gable", "hard gable", "硬山" -> RoofTypeKind.GABLE;
            case "double_gable", "doublegable", "shuangpo", "双坡" -> RoofTypeKind.DOUBLE_GABLE;
            case "hip", "hipped" -> RoofTypeKind.HIP;
            case "xieshan", "xie_shan", "xie-shan", "hip_and_gable", "hip-gable", "歇山" -> RoofTypeKind.XIESHAN;
            case "xuanshan", "xuan_shan", "xuan-shan", "overhang_gable", "悬山" -> RoofTypeKind.XUANSHAN;
            case "pyramid" -> RoofTypeKind.PYRAMID;
            case "cone", "conical" -> RoofTypeKind.CONE;
            case "dome", "domed", "curved" -> RoofTypeKind.DOME;
            case "mansard", "mansard_roof", "baroque_roof" -> RoofTypeKind.MANSARD;
            case "sawtooth", "saw_tooth", "northlight", "锯齿形", "锯齿" -> RoofTypeKind.SAWTOOTH;
            default -> RoofTypeKind.FLAT;
        };
    }
}
