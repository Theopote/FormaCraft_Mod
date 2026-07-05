package com.formacraft.common.generation.component.util;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 冠部 REVOLVE_SURFACE 模板库。LLM / enrich 只选 preset id，不手写 profile 坐标。
 */
public final class CrownTemplateLibrary {

    public static final String CLASSICAL_CUPOLA = "CLASSICAL_CUPOLA";
    public static final String ONION_DOME = "ONION_DOME";
    public static final String SIMPLE_DOME = "SIMPLE_DOME";

    private static final Map<String, List<double[]>> PROFILES = Map.of(
            CLASSICAL_CUPOLA, List.of(
                    pt(0.42, 0.0),
                    pt(0.48, 0.08),
                    pt(0.50, 0.22),
                    pt(0.46, 0.45),
                    pt(0.28, 0.72),
                    pt(0.10, 0.88),
                    pt(0.04, 1.0)
            ),
            ONION_DOME, List.of(
                    pt(0.38, 0.0),
                    pt(0.44, 0.10),
                    pt(0.58, 0.28),
                    pt(0.62, 0.42),
                    pt(0.40, 0.58),
                    pt(0.22, 0.72),
                    pt(0.34, 0.86),
                    pt(0.08, 1.0)
            ),
            SIMPLE_DOME, List.of(
                    pt(0.50, 0.0),
                    pt(0.49, 0.15),
                    pt(0.42, 0.35),
                    pt(0.28, 0.58),
                    pt(0.12, 0.78),
                    pt(0.0, 1.0)
            )
    );

    private CrownTemplateLibrary() {}

    public static List<double[]> profile(String templateId) {
        if (templateId == null || templateId.isBlank()) {
            return PROFILES.get(SIMPLE_DOME);
        }
        String key = templateId.trim().toUpperCase(Locale.ROOT);
        return PROFILES.getOrDefault(key, PROFILES.get(SIMPLE_DOME));
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return SIMPLE_DOME;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        if (PROFILES.containsKey(key)) {
            return key;
        }
        if (key.contains("ONION") || key.contains("BAROQUE") || key.contains("ORTHODOX")) {
            return ONION_DOME;
        }
        if (key.contains("CUPOLA") || key.contains("CLASSICAL") || key.contains("MONUMENT")) {
            return CLASSICAL_CUPOLA;
        }
        if (key.contains("DOME") || key.contains("HEMI")) {
            return SIMPLE_DOME;
        }
        return SIMPLE_DOME;
    }

    private static double[] pt(double r, double y) {
        return new double[] {r, y};
    }
}
