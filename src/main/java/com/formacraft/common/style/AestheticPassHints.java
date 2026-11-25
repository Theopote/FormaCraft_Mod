package com.formacraft.common.style;

import com.formacraft.common.generation.design.DesignDecisionPriority;
import com.formacraft.common.generation.design.DesignDecisionPriority.AestheticParamClass;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.StyleAttributes;

import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Optional aesthetic post-pass knobs. Missing → style-derived {@link DesignDecisionPriority#STYLE_DEFAULT}.
 * Explicit none/flat/off/false/0 always wins ({@link DesignDecisionPriority#USER_EXPLICIT}).
 * Style defaults never overwrite disable or invalid markers — see
 * {@link DesignDecisionPriority#styleDefaultMayFill} / {@link DesignDecisionPriority#classifyAestheticParam}.
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

        FacadeRelief relief = resolveField(
                raw(params, "facade_relief", "facadeRelief"),
                attr(attrs, StyleAttributes::facadeRelief),
                custom(attrs, "facade_relief"),
                AestheticPassHints::parseRelief,
                FacadeRelief.FLAT,
                () -> {
                    if (ExplicitDesignPolicy.noComplexDecor(plan, params)) return FacadeRelief.FLAT;
                    if (isModern(profile)) return FacadeRelief.SUBTLE;
                    if (isClassical(profile)) return FacadeRelief.DEEP;
                    return FacadeRelief.SUBTLE;
                });

        WindowTrim trim = resolveField(
                raw(params, "window_trim", "windowTrim"),
                attr(attrs, StyleAttributes::windowTrim),
                custom(attrs, "window_trim"),
                AestheticPassHints::parseTrim,
                WindowTrim.NONE,
                () -> {
                    if (ExplicitDesignPolicy.noComplexDecor(plan, params)) return WindowTrim.NONE;
                    if (profile.contains("gothic") || profile.contains("islamic") || profile.contains("roman"))
                        return WindowTrim.ARCH;
                    if (isModern(profile)) return WindowTrim.MODERN;
                    if (isEastAsian(profile)) return WindowTrim.EAST_ASIAN;
                    return WindowTrim.CLASSIC;
                });

        Weathering weathering = resolveField(
                raw(params, "weathering"),
                attr(attrs, StyleAttributes::weathering),
                custom(attrs, "weathering"),
                AestheticPassHints::parseWeathering,
                Weathering.NONE,
                () -> {
                    if (isModern(profile) || profile.contains("concrete") || profile.contains("cyber"))
                        return Weathering.NONE;
                    if (profile.contains("ruin") || profile.contains("castle") || profile.contains("aged"))
                        return Weathering.HEAVY;
                    return Weathering.SUBTLE;
                });

        LightingStyle lighting = resolveField(
                raw(params, "lighting_style", "lightingStyle"),
                attr(attrs, StyleAttributes::lightingStyle),
                custom(attrs, "lighting_style"),
                AestheticPassHints::parseLighting,
                LightingStyle.NONE,
                () -> {
                    if (ExplicitDesignPolicy.noComplexDecor(plan, params)) return LightingStyle.NONE;
                    if (profile.contains("gothic") || profile.contains("dark") || profile.contains("nether"))
                        return LightingStyle.SOUL_GOTHIC;
                    if (isModern(profile)) return LightingStyle.MODERN_SLEEK;
                    return LightingStyle.WARM_LANTERN;
                });

        LightingDensity density = resolveField(
                raw(params, "lighting_density", "lightingDensity"),
                attr(attrs, StyleAttributes::lightingDensity),
                custom(attrs, "lighting_density"),
                AestheticPassHints::parseDensity,
                LightingDensity.LOW,
                () -> LightingDensity.NORMAL);

        return new Resolved(relief, trim, weathering, lighting, density);
    }

    /**
     * Shared ABSENT / VALID / DISABLED / INVALID resolution for one aesthetic field.
     * DISABLED → {@code disabledValue}; INVALID → no STYLE_DEFAULT; ABSENT → styleDefault may fill.
     */
    private static <T> T resolveField(
            Object paramRaw,
            String attrRaw,
            String customRaw,
            Function<String, T> parser,
            T disabledValue,
            java.util.function.Supplier<T> styleDefault
    ) {
        Object winning = firstRaw(paramRaw, attrRaw, customRaw);
        AestheticParamClass cls = DesignDecisionPriority.classifyAestheticParam(winning);
        if (cls == AestheticParamClass.DISABLED) {
            return disabledValue;
        }
        if (cls == AestheticParamClass.ABSENT) {
            return styleDefault.get();
        }
        // VALID parse candidate (or Boolean true / non-disable token).
        T parsed = parser.apply(stringify(winning));
        if (parsed != null) {
            return parsed;
        }
        // INVALID: keep prior absent (null) — never silent STYLE_DEFAULT.
        return null;
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

    private static Object raw(Map<String, Object> params, String... keys) {
        if (params == null) return null;
        for (String k : keys) {
            if (params.containsKey(k)) return params.get(k);
        }
        return null;
    }

    private static String attr(StyleAttributes attrs, Function<StyleAttributes, String> f) {
        if (attrs == null) return null;
        String v = f.apply(attrs);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String custom(StyleAttributes attrs, String key) {
        if (attrs == null || attrs.customAttributes() == null) return null;
        String v = attrs.customAttributes().get(key);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static Object firstRaw(Object... values) {
        for (Object v : values) {
            if (v == null) continue;
            if (v instanceof String s && s.isBlank()) continue;
            return v;
        }
        return null;
    }

    private static String stringify(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Boolean b) return b ? "true" : "false";
        return String.valueOf(raw).trim();
    }

    private static FacadeRelief parseRelief(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "flat", "none", "off", "0", "false" -> FacadeRelief.FLAT;
            case "subtle", "low", "shallow", "true" -> FacadeRelief.SUBTLE;
            case "deep", "full", "high" -> FacadeRelief.DEEP;
            default -> null;
        };
    }

    private static WindowTrim parseTrim(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "none", "off", "false", "0" -> WindowTrim.NONE;
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
            case "none", "off", "clean", "false", "0" -> Weathering.NONE;
            case "subtle", "low", "light" -> Weathering.SUBTLE;
            case "medium", "normal" -> Weathering.MEDIUM;
            case "heavy", "high", "ruin" -> Weathering.HEAVY;
            default -> null;
        };
    }

    private static LightingStyle parseLighting(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "none", "off", "false", "0", "无灯", "no_lights" -> LightingStyle.NONE;
            case "warm_lantern", "lantern", "warm", "classic" -> LightingStyle.WARM_LANTERN;
            case "modern_sleek", "modern", "sleek", "end_rod" -> LightingStyle.MODERN_SLEEK;
            case "soul_gothic", "soul", "gothic", "dark" -> LightingStyle.SOUL_GOTHIC;
            default -> null;
        };
    }

    private static LightingDensity parseDensity(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "low", "sparse", "none", "off", "false", "0" -> LightingDensity.LOW;
            case "high", "dense" -> LightingDensity.HIGH;
            case "normal", "medium", "default" -> LightingDensity.NORMAL;
            default -> null;
        };
    }
}
