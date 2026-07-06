package com.formacraft.common.proportion;

import com.formacraft.common.facade.rhythm.RepeatingPattern;
import com.formacraft.common.facade.rhythm.RepeatingPatternDefaults;
import com.formacraft.common.facade.rhythm.RepeatingPatternParser;
import com.formacraft.common.generation.component.impl.WindowAspect;
import com.formacraft.common.generation.component.util.ComponentFacadeRhythmPlanner;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * M3+：proportion card / proportion_hints → window_aspect、window_ratio、void_ratio 注入与钳制。
 */
public final class OpeningGrammarResolver {

    private OpeningGrammarResolver() {}

    public static Component apply(LlmPlan plan, Component component) {
        if (plan == null || component == null) {
            return component;
        }
        String type = normalizeType(component.componentType());
        boolean facade = "FACADE_WINDOWS".equals(type);
        boolean mass = isMassType(type);
        boolean wall = "WALL".equals(type);
        if (!facade && !mass && !wall) {
            return component;
        }

        Map<String, Object> hints = plan.proportionHints() != null ? plan.proportionHints() : Map.of();
        ProportionCardRegistry.ProportionCard card = resolveCard(hints);

        Map<String, Object> params = new HashMap<>();
        if (component.params() != null) {
            params.putAll(component.params());
        }
        boolean changed = false;

        String aspect = getParamString(params, "window_aspect", "windowAspect");
        if (isBlank(aspect)) {
            Object hintAspect = hints.get("window_aspect");
            if (hintAspect != null && !String.valueOf(hintAspect).isBlank()) {
                params.put("window_aspect", String.valueOf(hintAspect).trim());
                changed = true;
            } else if (card != null && card.openingGrammar() != null) {
                String fallback = firstAllowedAspect(card);
                if (fallback != null) {
                    params.put("window_aspect", fallback);
                    changed = true;
                }
            }
        } else if (card != null && card.openingGrammar() != null) {
            String clamped = clampAspect(aspect, card);
            if (!aspect.equalsIgnoreCase(clamped)) {
                params.put("window_aspect", clamped);
                changed = true;
            }
        }

        if (facade || mass) {
            Double windowRatio = ComponentParamParsers.doubleOrNull(params, "window_ratio", "windowRatio");
            if (windowRatio == null) {
                Object hintRatio = hints.get("window_wall_ratio");
                if (hintRatio instanceof Number n) {
                    params.put("window_ratio", clampRatio(n.doubleValue(), card, "window_wall_ratio"));
                    changed = true;
                } else if (card != null && card.ratios() != null) {
                    ProportionCardRegistry.RatioSpec spec = card.ratios().get("window_wall_ratio");
                    if (spec != null) {
                        params.put("window_ratio", spec.ideal());
                        changed = true;
                    }
                }
            } else {
                double clamped = clampRatio(windowRatio, card, "window_wall_ratio");
                if (Math.abs(clamped - windowRatio) > 1e-6) {
                    params.put("window_ratio", clamped);
                    changed = true;
                }
            }
        }

        if (facade || mass || wall) {
            changed |= applyVoidRatio(params, card, hints);
        }

        if (facade || mass) {
            changed |= applyRepeatingPattern(params, hints, card);
            changed |= applyFacadeProfileFromPattern(params);
        }

        if (facade) {
            changed |= applyRhythmPreset(params, card, hints);
            changed |= applyWindowOrder(params, card, hints);
        }

        if (!changed) {
            return component;
        }
        return new Component(
                component.componentType(),
                component.slotId(),
                component.relativePosition(),
                component.dimensions(),
                component.features(),
                params
        );
    }

    private static ProportionCardRegistry.ProportionCard resolveCard(Map<String, Object> hints) {
        Object typology = hints.get("typology");
        if (typology instanceof String t && !t.isBlank()) {
            for (ProportionCardRegistry.ProportionCard card : ProportionCardRegistry.listCards()) {
                if (t.equalsIgnoreCase(card.typology()) || t.equalsIgnoreCase(card.id())) {
                    return card;
                }
            }
        }
        Object cardId = hints.get("proportion_card_id");
        if (cardId == null) {
            cardId = hints.get("proportionCardId");
        }
        if (cardId instanceof String id && !id.isBlank()) {
            return ProportionCardRegistry.getById(id);
        }
        return null;
    }

    private static String firstAllowedAspect(ProportionCardRegistry.ProportionCard card) {
        if (card.openingGrammar() == null || card.openingGrammar().windowAspect() == null) {
            return null;
        }
        List<String> allowed = card.openingGrammar().windowAspect();
        return allowed.isEmpty() ? null : allowed.getFirst();
    }

    private static String clampAspect(String aspect, ProportionCardRegistry.ProportionCard card) {
        List<String> allowed = card.openingGrammar().windowAspect();
        if (allowed == null || allowed.isEmpty()) {
            return aspect;
        }
        String normalized = aspect.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        for (String a : allowed) {
            if (a != null && a.trim().equalsIgnoreCase(normalized)) {
                return a;
            }
        }
        WindowAspect parsed = WindowAspect.parse(aspect);
        for (String a : allowed) {
            if (a != null && WindowAspect.parse(a) == parsed) {
                return a;
            }
        }
        return allowed.getFirst();
    }

    private static boolean applyVoidRatio(
            Map<String, Object> params,
            ProportionCardRegistry.ProportionCard card,
            Map<String, Object> hints
    ) {
        Double maxVoid = resolveMaxVoidRatio(card, hints);
        if (maxVoid == null) {
            return false;
        }
        Double voidRatio = ComponentParamParsers.doubleOrNull(params, "void_ratio", "voidRatio");
        if (voidRatio == null) {
            Object hintVoid = hints.get("void_ratio");
            if (hintVoid == null) {
                hintVoid = hints.get("max_void_ratio");
            }
            if (hintVoid instanceof Number n) {
                params.put("void_ratio", Math.min(n.doubleValue(), maxVoid));
                return true;
            }
            return false;
        }
        if (voidRatio > maxVoid + 1e-6) {
            params.put("void_ratio", maxVoid);
            return true;
        }
        return false;
    }

    private static boolean applyRepeatingPattern(
            Map<String, Object> params,
            Map<String, Object> hints,
            ProportionCardRegistry.ProportionCard card
    ) {
        if (RepeatingPatternParser.parse(params) != null) {
            return false;
        }
        Object raw = hints.get("repeating_pattern");
        if (raw == null) {
            raw = hints.get("repeatingPattern");
        }
        if (raw == null) {
            RepeatingPattern suggested = RepeatingPatternDefaults.suggestFromHints(hints, card);
            if (suggested == null) {
                return false;
            }
            params.put("repeating_pattern", RepeatingPatternParser.toParamsMap(suggested));
            return true;
        }
        params.put("repeating_pattern", raw);
        return true;
    }

    private static boolean applyFacadeProfileFromPattern(Map<String, Object> params) {
        if (!isBlank(getParamString(params, "facade_profile", "facadeProfile", "facade"))) {
            return false;
        }
        RepeatingPattern pattern = RepeatingPatternParser.parse(params);
        if (!RepeatingPatternDefaults.hasPillarElements(pattern)) {
            return false;
        }
        params.put("facade_profile", "base_plinth,vertical_pilasters");
        return true;
    }

    private static boolean applyRhythmPreset(
            Map<String, Object> params,
            ProportionCardRegistry.ProportionCard card,
            Map<String, Object> hints
    ) {
        if (!isBlank(getParamString(params, "rhythm_preset", "rhythmPreset"))) {
            return false;
        }
        Object hintPreset = hints.get("rhythm_preset");
        if (hintPreset == null) {
            hintPreset = hints.get("rhythmPreset");
        }
        if (hintPreset instanceof String s && !s.isBlank()) {
            params.put("rhythm_preset", s.trim());
            return true;
        }
        String aspect = getParamString(params, "window_aspect", "windowAspect");
        if ("vertical_bay".equalsIgnoreCase(aspect)) {
            params.put("rhythm_preset", ComponentFacadeRhythmPlanner.PRESET_CLASSICAL_PILASTER_BAY);
            return true;
        }
        if (card != null) {
            String typology = card.typology() != null ? card.typology().toLowerCase(Locale.ROOT) : "";
            if (typology.contains("classical") || typology.contains("monument") || typology.contains("palace")) {
                params.put("rhythm_preset", ComponentFacadeRhythmPlanner.PRESET_CLASSICAL_PILASTER_BAY);
                return true;
            }
        }
        String facadeProfile = getParamString(params, "facade_profile", "facadeProfile");
        if (facadeProfile != null) {
            String fp = facadeProfile.toLowerCase(Locale.ROOT);
            if (fp.contains("pilaster") || fp.contains("colonnade")) {
                params.put("rhythm_preset", ComponentFacadeRhythmPlanner.PRESET_CLASSICAL_PILASTER_BAY);
                return true;
            }
        }
        return false;
    }

    private static boolean applyWindowOrder(
            Map<String, Object> params,
            ProportionCardRegistry.ProportionCard card,
            Map<String, Object> hints
    ) {
        if (!isBlank(getParamString(params, "window_order", "windowOrder"))) {
            return false;
        }
        Object hint = hints.get("window_order");
        if (hint == null) {
            hint = hints.get("windowOrder");
        }
        if (hint instanceof String s && !s.isBlank()) {
            params.put("window_order", s.trim());
            return true;
        }
        if (card != null) {
            String typology = card.typology() != null ? card.typology().toLowerCase(Locale.ROOT) : "";
            if (typology.contains("classical") || typology.contains("monument") || typology.contains("castle")) {
                params.put("window_order", "full");
                return true;
            }
        }
        String aspect = getParamString(params, "window_aspect", "windowAspect");
        if ("vertical_bay".equalsIgnoreCase(aspect)) {
            params.put("window_order", "medium");
            return true;
        }
        return false;
    }

    private static Double resolveMaxVoidRatio(
            ProportionCardRegistry.ProportionCard card,
            Map<String, Object> hints
    ) {
        Double max = null;
        if (card != null && card.openingGrammar() != null) {
            max = card.openingGrammar().maxVoidRatio();
        }
        Object hintMax = hints.get("max_void_ratio");
        if (hintMax instanceof Number n) {
            double hintVal = n.doubleValue();
            max = max == null ? hintVal : Math.min(max, hintVal);
        }
        return max;
    }

    private static double clampRatio(
            double value,
            ProportionCardRegistry.ProportionCard card,
            String ratioKey
    ) {
        if (card == null || card.ratios() == null) {
            return value;
        }
        ProportionCardRegistry.RatioSpec spec = card.ratios().get(ratioKey);
        if (spec == null) {
            return value;
        }
        return Math.max(spec.min(), Math.min(spec.max(), value));
    }

    private static boolean isMassType(String type) {
        return type != null && (type.startsWith("MASS") || "HOUSE".equals(type) || "BUILDING".equals(type));
    }

    private static String normalizeType(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank() || "none".equalsIgnoreCase(value.trim())
                || "default".equalsIgnoreCase(value.trim());
    }

    private static String getParamString(Map<String, Object> params, String... keys) {
        if (params == null) {
            return null;
        }
        for (String key : keys) {
            Object v = params.get(key);
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }
}
