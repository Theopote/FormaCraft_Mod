package com.formacraft.common.style;

import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.StyleAttributes;

import java.util.Locale;
import java.util.Map;

/**
 * Optional aesthetic post-pass knobs. Missing → style-derived TECHNICAL_DEFAULT.
 * Explicit none/flat/off always wins (USER_EXPLICIT).
 */
public final class AestheticPassHints {
    private AestheticPassHints() {}

    public enum FacadeRelief { FLAT, SUBTLE, DEEP }
    public enum WindowTrim { NONE, CLASSIC, ARCH, MODERN, EAST_ASIAN }
    public enum Weathering { NONE, SUBTLE, MEDIUM, HEAVY }
    public enum LightingStyle { NONE, WARM_LANTERN, MODERN_SLEEK, SOUL_GOTHIC }
    public enum LightingDensity { LOW, NORMAL, HIGH }

    public record Resolved(
            FacadeRelief facadeRelief,
            WindowTrim windowTrim,
            Weathering weathering,
            LightingStyle lightingStyle,
            LightingDensity lightingDensity
    ) {}

    public static Resolved resolve(LlmPlan plan, Map<String, Object> params) {
        String profile = plan != null && plan.styleProfile() != null
                ? plan.styleProfile().toLowerCase(Locale.ROOT) : "";
        StyleAttributes attrs = plan != null ? plan.styleAttributes() : null;

        FacadeRelief relief = parseRelief(first(
                param(params, "facade_relief", "facadeRelief"),
                attr(attrs, a -> a.facadeRelief()),
                custom(attrs, "facade_relief")));
        if (relief == null) {
            if (ExplicitDesignPolicy.noComplexDecor(plan, params)) relief = FacadeRelief.FLAT;
            else if (isModern(profile)) relief = FacadeRelief.SUBTLE;
            else if (isClassical(profile)) relief = FacadeRelief.DEEP;
            else relief = FacadeRelief.SUBTLE;
        }

        WindowTrim trim = parseTrim(first(
                param(params, "window_trim", "windowTrim"),
                attr(attrs, a -> a.windowTrim()),
                custom(attrs, "window_trim")));
        if (trim == null) {
            if (ExplicitDesignPolicy.noComplexDecor(plan, params)) trim = WindowTrim.NONE;
            else if (profile.contains("gothic") || profile.contains("islamic") || profile.contains("roman"))
                trim = WindowTrim.ARCH;
            else if (isModern(profile)) trim = WindowTrim.MODERN;
            else if (isEastAsian(profile)) trim = WindowTrim.EAST_ASIAN;
            else trim = WindowTrim.CLASSIC;
        }

        Weathering weathering = parseWeathering(first(
                param(params, "weathering"),
                attr(attrs, a -> a.weathering()),
                custom(attrs, "weathering")));
        if (weathering == null) {
            if (isModern(profile) || profile.contains("concrete") || profile.contains("cyber"))
                weathering = Weathering.NONE;
            else if (profile.contains("ruin") || profile.contains("castle") || profile.contains("aged"))
                weathering = Weathering.HEAVY;
            else weathering = Weathering.SUBTLE;
        }

        LightingStyle lighting = parseLighting(first(
                param(params, "lighting_style", "lightingStyle"),
                attr(attrs, a -> a.lightingStyle()),
                custom(attrs, "lighting_style")));
        if (lighting == null) {
            // USER_EXPLICIT no_complex_decor wins over style-derived lantern defaults.
            if (ExplicitDesignPolicy.noComplexDecor(plan, params)) lighting = LightingStyle.NONE;
            else if (profile.contains("gothic") || profile.contains("dark") || profile.contains("nether"))
                lighting = LightingStyle.SOUL_GOTHIC;
            else if (isModern(profile)) lighting = LightingStyle.MODERN_SLEEK;
            else lighting = LightingStyle.WARM_LANTERN;
        }

        LightingDensity density = parseDensity(first(
                param(params, "lighting_density", "lightingDensity"),
                attr(attrs, a -> a.lightingDensity()),
                custom(attrs, "lighting_density")));
        if (density == null) density = LightingDensity.NORMAL;

        return new Resolved(relief, trim, weathering, lighting, density);
    }

    private static boolean isModern(String p) {
        return p.contains("modern") || p.contains("minimal") || p.contains("bauhaus")
                || p.contains("concrete") || p.contains("cyber") || p.contains("glass");
    }

    private static boolean isClassical(String p) {
        return p.contains("classic") || p.contains("medieval") || p.contains("gothic")
                || p.contains("baroque") || p.contains("roman") || p.contains("hui")
                || p.contains("chinese") || p.contains("japanese") || p.contains("east");
    }

    private static boolean isEastAsian(String p) {
        return p.contains("chinese") || p.contains("japanese") || p.contains("korean")
                || p.contains("hui") || p.contains("east_asian") || p.contains("oriental");
    }

    private static String param(Map<String, Object> params, String... keys) {
        if (params == null) return null;
        for (String k : keys) {
            Object v = params.get(k);
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) return s;
            }
        }
        return null;
    }

    private static String attr(StyleAttributes attrs, java.util.function.Function<StyleAttributes, String> f) {
        if (attrs == null) return null;
        String v = f.apply(attrs);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String custom(StyleAttributes attrs, String key) {
        if (attrs == null || attrs.customAttributes() == null) return null;
        String v = attrs.customAttributes().get(key);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String first(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static FacadeRelief parseRelief(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "flat", "none", "off", "0" -> FacadeRelief.FLAT;
            case "subtle", "low", "shallow" -> FacadeRelief.SUBTLE;
            case "deep", "full", "high" -> FacadeRelief.DEEP;
            default -> null;
        };
    }

    private static WindowTrim parseTrim(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "none", "off", "false" -> WindowTrim.NONE;
            case "classic", "classical", "medieval" -> WindowTrim.CLASSIC;
            case "arch", "gothic", "islamic", "roman" -> WindowTrim.ARCH;
            case "modern", "minimal", "brise" -> WindowTrim.MODERN;
            case "east_asian", "east-asian", "chinese", "japanese", "hui" -> WindowTrim.EAST_ASIAN;
            default -> null;
        };
    }

    private static Weathering parseWeathering(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "none", "off", "clean" -> Weathering.NONE;
            case "subtle", "low", "light" -> Weathering.SUBTLE;
            case "medium", "normal" -> Weathering.MEDIUM;
            case "heavy", "high", "ruin" -> Weathering.HEAVY;
            default -> null;
        };
    }

    private static LightingStyle parseLighting(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "none", "off", "无灯", "no_lights" -> LightingStyle.NONE;
            case "warm_lantern", "lantern", "warm", "classic" -> LightingStyle.WARM_LANTERN;
            case "modern_sleek", "modern", "sleek", "end_rod" -> LightingStyle.MODERN_SLEEK;
            case "soul_gothic", "soul", "gothic", "dark" -> LightingStyle.SOUL_GOTHIC;
            default -> null;
        };
    }

    private static LightingDensity parseDensity(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "low", "sparse" -> LightingDensity.LOW;
            case "high", "dense" -> LightingDensity.HIGH;
            case "normal", "medium", "default" -> LightingDensity.NORMAL;
            default -> null;
        };
    }
}
