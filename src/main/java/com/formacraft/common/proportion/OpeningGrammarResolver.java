package com.formacraft.common.proportion;

import com.formacraft.common.generation.component.impl.WindowAspect;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * M3：将 proportion card / proportion_hints 的 openingGrammar 注入 FACADE_WINDOWS 与 MASS 组件。
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
        if (!facade && !mass) {
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
                    params.put("window_ratio", n.doubleValue());
                    changed = true;
                } else if (card != null && card.ratios() != null) {
                    ProportionCardRegistry.RatioSpec spec = card.ratios().get("window_wall_ratio");
                    if (spec != null) {
                        params.put("window_ratio", spec.ideal());
                        changed = true;
                    }
                }
            }
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
