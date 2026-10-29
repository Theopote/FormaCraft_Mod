package com.formacraft.common.generation.component.util;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import java.util.Locale;

/** Roof cross sections, independent of palette. Authored height and pitch remain authoritative. */
public final class StyleRoofGeometry {
    private StyleRoofGeometry() {}

    private static String identity(SemanticComponent semantic) {
        String style = semantic.styleProfile();
        if (style == null || style.isBlank() || "DEFAULT".equalsIgnoreCase(style)) {
            style = String.valueOf(semantic.source().features());
            if (semantic.genome() != null && semantic.genome().culturalStyle != null) {
                var culture = semantic.genome().culturalStyle;
                style += " " + culture.region + " " + culture.keywords;
            }
        }
        return style.toLowerCase(Locale.ROOT);
    }

    public static double profile(SemanticComponent semantic, double progress) {
        double t = Math.clamp(progress, 0.0, 1.0);
        var params = semantic.source().params();
        Object authored = params == null ? null : params.getOrDefault("roof_slope_profile", params.get("roofSlopeProfile"));
        String style = authored == null ? identity(semantic) : authored.toString().toLowerCase(Locale.ROOT);
        if (style.contains("linear") || style.equals("none")) return t;
        // Keep the same eave and ridge elevations; vary how quickly the slope rises.
        if (style.contains("thai") || style.contains("泰式")) return Math.pow(t, 2.1);
        if (style.contains("japanese") || style.contains("日式")) return t;
        if (style.contains("hui") || style.contains("徽")) return Math.pow(t, 1.0);
        if (style.contains("chinese") || style.contains("中式") || style.contains("curved")) return Math.pow(t, 1.7);
        if (style.contains("gothic") || style.contains("哥特")) return Math.pow(t, .72);
        return t;
    }

    /** Only used for inferred roofs whose rise was not authored. */
    public static int defaultRise(String identity, int width, int depth, int fallback) {
        String s = identity == null ? "" : identity.toLowerCase(Locale.ROOT);
        double ratio = s.contains("gothic") ? .75 : s.contains("thai") ? .6
                : s.contains("japanese") ? .28 : s.contains("modern") ? .15 : 0;
        return ratio == 0 ? fallback : Math.max(2, Math.min(24, (int) Math.round(Math.min(width, depth) * ratio)));
    }
}
