package com.formacraft.common.alignment;

import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses {@code alignment_and_symmetry} from {@link LlmPlan} top-level field or proportion_hints.
 */
public final class AlignmentContractParser {

    private AlignmentContractParser() {}

    public static AlignmentAndSymmetry resolve(LlmPlan plan) {
        if (plan == null) {
            return null;
        }
        if (plan.alignmentAndSymmetry() != null && plan.alignmentAndSymmetry().hasContent()) {
            return plan.alignmentAndSymmetry();
        }
        if (plan.proportionHints() != null) {
            Object raw = plan.proportionHints().get("alignment_and_symmetry");
            if (raw == null) {
                raw = plan.proportionHints().get("alignmentAndSymmetry");
            }
            AlignmentAndSymmetry parsed = parseValue(raw);
            if (parsed != null) {
                return parsed;
            }
        }
        return inferFromGlobalConstraints(plan);
    }

    public static AlignmentAndSymmetry parseValue(Object raw) {
        switch (raw) {
            case null -> {
                return null;
            }
            case AlignmentAndSymmetry contract -> {
                return contract.hasContent() ? contract : null;
            }
            case Map<?, ?> map -> {
                try {
                    AlignmentAndSymmetry parsed = JsonUtil.fromJson(JsonUtil.toJson(map), AlignmentAndSymmetry.class);
                    return parsed != null && parsed.hasContent() ? parsed : null;
                } catch (Throwable ignored) {
                    return parseMapManually(map);
                }
            }
            default -> {
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static AlignmentAndSymmetry parseMapManually(Map<?, ?> map) {
        Map<String, Object> root = (Map<String, Object>) map;
        String symmetryType = stringOrNull(root.get("symmetry_type"), root.get("symmetryType"));
        Integer centerX = intOrNull(root, "center_axis_x", "centerAxisX");
        Integer centerZ = intOrNull(root, "center_axis_z", "centerAxisZ");
        BayRhythm rhythmX = parseRhythm(root.get("rhythm_x"), root.get("rhythmX"));
        BayRhythm rhythmZ = parseRhythm(root.get("rhythm_z"), root.get("rhythmZ"));
        AlignmentAndSymmetry contract = new AlignmentAndSymmetry(symmetryType, centerX, centerZ, rhythmX, rhythmZ);
        return contract.hasContent() ? contract : null;
    }

    private static BayRhythm parseRhythm(Object primary, Object secondary) {
        Object raw = primary != null ? primary : secondary;
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> rhythmMap = (Map<String, Object>) map;
        Integer bayCount = ComponentParamParsers.intOrNull(rhythmMap, "bay_count", "bayCount");
        Integer bayWidth = ComponentParamParsers.intOrNull(rhythmMap, "bay_width", "bayWidth");
        List<BaySpec> bays = new ArrayList<>();
        Object sideBays = rhythmMap.get("side_bays");
        if (sideBays == null) {
            sideBays = rhythmMap.get("sideBays");
        }
        if (sideBays instanceof List<?> list) {
            for (Object item : list) {
                BaySpec spec = parseBay(item);
                if (spec != null) {
                    bays.add(spec);
                }
            }
        }
        BayRhythm rhythm = new BayRhythm(bays.isEmpty() ? null : bays, bayCount, bayWidth);
        return rhythm.hasContent() ? rhythm : null;
    }

    private static BaySpec parseBay(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Object widthRaw = map.get("width");
        int width = 0;
        if (widthRaw instanceof Number n) {
            width = n.intValue();
        } else if (widthRaw != null) {
            try {
                width = Integer.parseInt(String.valueOf(widthRaw).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (width <= 0) {
            return null;
        }
        Object role = map.get("role");
        return new BaySpec(width, role == null ? null : String.valueOf(role).trim());
    }

    private static AlignmentAndSymmetry inferFromGlobalConstraints(LlmPlan plan) {
        if (plan.globalConstraints() == null || plan.globalConstraints().symmetry() == null) {
            return null;
        }
        return switch (plan.globalConstraints().symmetry()) {
            case MIRROR_X -> new AlignmentAndSymmetry("bilateral_x", null, null, null, null);
            case MIRROR_Z -> new AlignmentAndSymmetry("bilateral_z", null, null, null, null);
            case RADIAL -> new AlignmentAndSymmetry("radial", null, null, null, null);
            case NONE -> null;
        };
    }

    private static Integer intOrNull(Map<String, Object> map, String... keys) {
        return ComponentParamParsers.intOrNull(map, keys);
    }

    private static String stringOrNull(Object... values) {
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String s = String.valueOf(value).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }
}
