package com.formacraft.common.typology;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Declares which plan {@code roof_type} values a typology generator can actually execute.
 * Classification: HARD_CONSTRAINT capability table — param equality is never proof of support.
 * Dense-eaves morphology always emits eave bands + finial; they are not removable via
 * {@code roof_type=none} without a dedicated generator mode (none exists today).
 */
public final class TypologyRoofCapability {
    private TypologyRoofCapability() {}

    /** Typologies whose positive Chinese hall roofs are owned by ChineseHallRoofGenerator. */
    private static final Set<String> CHINESE_HALL_FORMS = Set.of(
            "xieshan", "xie_shan", "歇山",
            "xuanshan", "xuan_shan", "悬山",
            "yingshan", "ying_shan", "硬山",
            "wudian", "wu_dian", "庑殿",
            "double_eave_xieshan", "double_eave_xie_shan", "重檐歇山"
    );

    public enum Support {
        /** Generator executes this roof_type (including omit). */
        SUPPORTED,
        /** Typology has no executable mode for this value — emit capability_gap. */
        UNSUPPORTED,
        /** No typology id / not a typology STRUCTURE — leave to other validators. */
        NOT_APPLICABLE
    }

    public static Support evaluate(String typologyId, String roofType) {
        if (typologyId == null || typologyId.isBlank()) {
            return Support.NOT_APPLICABLE;
        }
        if (roofType == null || roofType.isBlank()) {
            return Support.NOT_APPLICABLE;
        }
        String tid = typologyId.trim().toLowerCase(Locale.ROOT);
        String roof = roofType.trim().toLowerCase(Locale.ROOT);
        return switch (tid) {
            case "dense_eaves_pagoda" ->
                    // Intrinsic dense eaves + finial are not plan roof_type modes.
                    Support.UNSUPPORTED;
            case "tailiang_timber_hall" -> {
                if ("none".equals(roof)) {
                    yield Support.SUPPORTED; // builder skips ChineseHallRoofGenerator
                }
                yield CHINESE_HALL_FORMS.contains(roof) || roof.contains("double_eave")
                        ? Support.SUPPORTED
                        : Support.UNSUPPORTED;
            }
            case "courtyard_compound", "gothic_cathedral_hall" ->
                    // Positive/omit plan roof_type is not a verified executable mode today.
                    Support.UNSUPPORTED;
            default -> Support.UNSUPPORTED;
        };
    }

    public static Optional<String> unsupportedReason(String typologyId, String roofType) {
        if (evaluate(typologyId, roofType) != Support.UNSUPPORTED) {
            return Optional.empty();
        }
        String tid = typologyId == null ? "" : typologyId.trim();
        String roof = roofType == null ? "" : roofType.trim();
        if ("dense_eaves_pagoda".equalsIgnoreCase(tid)) {
            if ("none".equalsIgnoreCase(roof)) {
                return Optional.of("dense_eaves_pagoda cannot omit intrinsic eave bands/finial; capability_gap");
            }
            return Optional.of("dense_eaves_pagoda does not execute plan roof_type=" + roof + "; capability_gap");
        }
        return Optional.of("typology " + tid + " cannot execute roof_type=" + roof + "; capability_gap");
    }
}
