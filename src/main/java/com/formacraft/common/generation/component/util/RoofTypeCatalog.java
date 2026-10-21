package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Canonical roof_type strings accepted when explicitly planned. */
public final class RoofTypeCatalog {
    private RoofTypeCatalog() {}

    /** Tokens accepted as explicit plan-level roof_type (LLM / design validation). */
    public static final Set<String> SUPPORTED_TOKENS = Set.of(
            "flat",
            "shed", "lean_to", "single_slope",
            "gable", "gabled", "gable_low_pitch", "low_pitch_gable",
            "yingshan", "ying_shan", "ying-shan", "hard_gable", "hard gable", "硬山",
            "double_gable", "doublegable", "shuangpo", "双坡",
            "hip", "hipped", "chinese_hip",
            "xieshan", "xie_shan", "xie-shan", "hip_and_gable", "hip-gable", "hip_gable", "歇山",
            "xuanshan", "xuan_shan", "xuan-shan", "overhang_gable", "悬山",
            "pyramid", "pyramidal",
            "cone", "conical",
            "dome", "domed", "curved", "polyhedral_dome", "faceted_dome", "polygonal_dome",
            "mansard", "mansard_roof", "baroque_roof",
            "gambrel", "gambrel_roof", "折线双坡顶",
            "barrel_vault", "barrel vault", "筒拱顶", "筒拱屋顶",
            "onion_dome", "onion dome", "洋葱穹顶",
            "steep_thatch", "steep thatch", "陡坡茅草顶",
            "none"
    );

    /**
     * Extra tokens the generator can execute (industrial specialty roofs) without
     * advertising them as general-purpose explicit plan tokens.
     */
    private static final Set<String> GENERATION_TOKENS = Set.of(
            "sawtooth", "saw_tooth", "sawtooth_skylight", "northlight", "锯齿形", "锯齿"
    );

    public static Optional<RoofTypeKind> parseExplicit(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("none".equals(normalized)) {
            return Optional.empty();
        }
        if (!SUPPORTED_TOKENS.contains(normalized) && !GENERATION_TOKENS.contains(normalized)) {
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
            for (String token : java.util.List.of("gambrel_roof", "gambrel", "折线双坡顶",
                    "barrel_vault", "barrel vault", "筒拱屋顶", "筒拱顶",
                    "onion_dome", "onion dome", "洋葱穹顶",
                    "steep_thatch", "steep thatch", "陡坡茅草顶")) {
                if (lower.contains(token)) return Optional.of(token);
            }
            if (lower.contains("yingshan") || lower.contains("ying_shan") || lower.contains("ying-shan")
                    || lower.contains("hard_gable") || lower.contains("hard gable") || lower.contains("硬山")) {
                return Optional.of("yingshan");
            }
            if (lower.contains("xuanshan") || lower.contains("xuan_shan") || lower.contains("悬山")) {
                return Optional.of("xuanshan");
            }
            if (lower.contains("xieshan") || lower.contains("xie_shan") || lower.contains("hip_and_gable") || lower.contains("hip_gable")
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

    /**
     * True when the generator can execute the token (plan-supported or specialty generation).
     * Use this in compile validation; {@link #isSupportedExplicit} stays the advertised plan set.
     */
    public static boolean isExecutableExplicit(String raw) {
        if (isSupportedExplicit(raw)) {
            return true;
        }
        if (raw == null || raw.isBlank()) {
            return true;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return GENERATION_TOKENS.contains(normalized);
    }

    private static RoofTypeKind mapToken(String normalized) {
        return switch (normalized) {
            case "gambrel", "gambrel_roof", "折线双坡顶" -> RoofTypeKind.GAMBREL;
            case "barrel_vault", "barrel vault", "筒拱顶", "筒拱屋顶" -> RoofTypeKind.BARREL_VAULT;
            case "onion_dome", "onion dome", "洋葱穹顶" -> RoofTypeKind.ONION_DOME;
            case "steep_thatch", "steep thatch", "陡坡茅草顶" -> RoofTypeKind.STEEP_THATCH;
            case "shed", "lean_to", "single_slope" -> RoofTypeKind.SHED;
            case "gable", "gabled", "gable_low_pitch", "low_pitch_gable", "yingshan", "ying_shan", "ying-shan", "hard_gable", "hard gable", "硬山" -> RoofTypeKind.GABLE;
            case "double_gable", "doublegable", "shuangpo", "双坡" -> RoofTypeKind.DOUBLE_GABLE;
            case "hip", "hipped", "chinese_hip" -> RoofTypeKind.HIP;
            case "xieshan", "xie_shan", "xie-shan", "hip_and_gable", "hip-gable", "hip_gable", "歇山" -> RoofTypeKind.XIESHAN;
            case "xuanshan", "xuan_shan", "xuan-shan", "overhang_gable", "悬山" -> RoofTypeKind.XUANSHAN;
            case "pyramid", "pyramidal" -> RoofTypeKind.PYRAMID;
            case "cone", "conical" -> RoofTypeKind.CONE;
            case "dome", "domed", "curved", "polyhedral_dome", "faceted_dome", "polygonal_dome" -> RoofTypeKind.DOME;
            case "mansard", "mansard_roof", "baroque_roof" -> RoofTypeKind.MANSARD;
            case "sawtooth", "saw_tooth", "sawtooth_skylight", "northlight", "锯齿形", "锯齿" -> RoofTypeKind.SAWTOOTH;
            default -> RoofTypeKind.FLAT;
        };
    }
}
