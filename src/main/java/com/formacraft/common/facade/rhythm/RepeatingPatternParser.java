package com.formacraft.common.facade.rhythm;

import com.formacraft.common.generation.component.util.ComponentParamParsers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses {@code repeating_pattern} from component params, BuildingSpec extra, or proportion hints.
 */
public final class RepeatingPatternParser {

    private RepeatingPatternParser() {}

    public static RepeatingPattern parse(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        Object raw = firstPresent(params, "repeating_pattern", "repeatingPattern");
        return parseValue(raw);
    }

    public static RepeatingPattern parseValue(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof RepeatingPattern pattern) {
            return pattern.isValid() ? pattern : null;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        return parseMap(map);
    }

    @SuppressWarnings("unchecked")
    private static RepeatingPattern parseMap(Map<?, ?> map) {
        Map<String, Object> params = (Map<String, Object>) map;
        Integer unitWidth = ComponentParamParsers.intOrNull(params, "unit_width_z", "unitWidthZ", "unit_width", "unitWidth");
        Object elementsRaw = firstPresent(params, "elements", "units", "segments");
        if (!(elementsRaw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }

        List<RepeatingPatternElement> elements = new ArrayList<>();
        int sumWidth = 0;
        for (Object item : list) {
            RepeatingPatternElement element = parseElement(item);
            if (element == null || element.width() <= 0 || element.type() == RepeatingPatternElement.Type.UNKNOWN) {
                return null;
            }
            elements.add(element);
            sumWidth += element.width();
        }

        if (unitWidth == null || unitWidth <= 0) {
            unitWidth = sumWidth;
        }
        RepeatingPattern pattern = new RepeatingPattern(unitWidth, elements);
        return pattern.isValid() ? pattern : null;
    }

    private static RepeatingPatternElement parseElement(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Object typeRaw = map.get("type");
        if (typeRaw == null) {
            typeRaw = map.get("kind");
        }
        RepeatingPatternElement.Type type = RepeatingPatternElement.Type.parse(typeRaw == null ? null : String.valueOf(typeRaw));
        int width = 0;
        Object widthRaw = map.get("width");
        if (widthRaw instanceof Number n) {
            width = n.intValue();
        } else if (widthRaw != null) {
            try {
                width = Integer.parseInt(String.valueOf(widthRaw).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        int inset = 0;
        Object insetRaw = map.get("inset");
        if (insetRaw instanceof Number n) {
            inset = n.intValue();
        } else if (insetRaw != null) {
            try {
                inset = Integer.parseInt(String.valueOf(insetRaw).trim());
            } catch (NumberFormatException ignored) {
                inset = 0;
            }
        }
        return new RepeatingPatternElement(type, width, inset);
    }

    private static Object firstPresent(Map<String, Object> params, String... keys) {
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            Object v = params.get(key);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    public static Map<String, Object> toParamsMap(RepeatingPattern pattern) {
        if (pattern == null || !pattern.isValid()) {
            return Map.of();
        }
        List<Map<String, Object>> elements = new ArrayList<>();
        for (RepeatingPatternElement element : pattern.elements()) {
            String type = switch (element.type()) {
                case PILLAR -> "pillar";
                case WINDOW -> "window";
                case SOLID -> "solid";
                case UNKNOWN -> "unknown";
            };
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("type", type);
            row.put("width", element.width());
            if (element.inset() > 0) {
                row.put("inset", element.inset());
            }
            elements.add(row);
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("unit_width_z", pattern.unitWidth());
        out.put("elements", elements);
        return out;
    }
}
