package com.formacraft.common.detail;

import com.formacraft.common.generation.component.util.ComponentFloorCorniceDecorator;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.semantic.SemanticPart;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses {@code proportion_hints.detail_rules} and injects built-in presets
 * (floor_cornice, base_plinth belt) when typology/enrichment flags apply.
 */
public final class DetailRuleParser {

    public static final String PRESET_FLOOR_CORNICE = "preset:floor_cornice";
    public static final String PRESET_BASE_PLINTH = "preset:base_plinth_top";

    private DetailRuleParser() {}

    public static List<DetailRule> resolve(LlmPlan plan) {
        if (plan == null) {
            return List.of();
        }
        List<DetailRule> rules = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        appendExplicitRules(plan, rules, seen);
        appendPresetRules(plan, rules, seen);

        return List.copyOf(rules);
    }

    @SuppressWarnings("unchecked")
    private static void appendExplicitRules(LlmPlan plan, List<DetailRule> rules, Set<String> seen) {
        Map<String, Object> hints = plan.proportionHints();
        if (hints == null) {
            return;
        }
        Object raw = firstPresent(hints, "detail_rules", "detailRules");
        if (!(raw instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            DetailRule rule = parseRuleMap((Map<String, Object>) map, null);
            if (rule != null && rule.isValid() && seen.add(ruleKey(rule))) {
                rules.add(rule);
            }
        }
    }

    private static void appendPresetRules(LlmPlan plan, List<DetailRule> rules, Set<String> seen) {
        if (ComponentFloorCorniceDecorator.shouldApply(plan)) {
            DetailRule rule = floorCornicePreset();
            if (seen.add(ruleKey(rule))) {
                rules.add(rule);
            }
        }
        if (shouldApplyBasePlinthPreset(plan)) {
            DetailRule rule = basePlinthPreset();
            if (seen.add(ruleKey(rule))) {
                rules.add(rule);
            }
        }
    }

    public static DetailRule floorCornicePreset() {
        return new DetailRule(
                new DetailRule.DetailRuleWhen(
                        DetailRuleRegion.PERIMETER,
                        DetailRuleYAnchor.FLOOR_BOUNDARY,
                        0,
                        "wall"
                ),
                new DetailRule.DetailRuleAction(
                        DetailRuleActionType.INVERTED_STAIRS,
                        SemanticPart.WALL_ACCENT,
                        null,
                        DetailRuleFacing.OUTWARD
                ),
                PRESET_FLOOR_CORNICE
        );
    }

    public static DetailRule basePlinthPreset() {
        return new DetailRule(
                new DetailRule.DetailRuleWhen(
                        DetailRuleRegion.PERIMETER,
                        DetailRuleYAnchor.BASE_TOP,
                        0,
                        "wall"
                ),
                new DetailRule.DetailRuleAction(
                        DetailRuleActionType.SLAB,
                        SemanticPart.FOUNDATION,
                        null,
                        DetailRuleFacing.NONE
                ),
                PRESET_BASE_PLINTH
        );
    }

    private static boolean shouldApplyBasePlinthPreset(LlmPlan plan) {
        Map<String, Object> hints = plan.proportionHints();
        if (hints != null) {
            if (isDisabled(hints.get("base_plinth_detail")) || isDisabled(hints.get("basePlinthDetail"))) {
                return false;
            }
            if (isEnabled(hints.get("base_plinth_detail")) || isEnabled(hints.get("basePlinthDetail"))) {
                return true;
            }
            if (hasBasePlinthMarker(String.valueOf(hints.getOrDefault("facade_profile", "")))) {
                return true;
            }
            String typology = String.valueOf(hints.getOrDefault("typology", "")).toLowerCase(Locale.ROOT);
            if (typology.contains("classical") || typology.contains("monument")
                    || typology.contains("palace") || typology.contains("civic")) {
                return true;
            }
        }
        if (plan.components() == null) {
            return false;
        }
        for (Component c : plan.components()) {
            if (c == null) {
                continue;
            }
            if (c.features() != null) {
                for (String feature : c.features()) {
                    if (feature != null && feature.toLowerCase(Locale.ROOT).contains("base_plinth")) {
                        return true;
                    }
                }
            }
            if (c.params() == null) {
                continue;
            }
            if (hasBasePlinthMarker(getParamString(c.params(), "facade_profile", "facadeProfile"))) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static DetailRule parseRuleMap(Map<String, Object> map, String presetId) {
        Object whenRaw = map.get("when");
        Object actionRaw = map.get("action");
        if (whenRaw == null) {
            whenRaw = map.get("if");
        }
        if (actionRaw == null) {
            actionRaw = map.get("then");
        }
        if (!(whenRaw instanceof Map<?, ?> whenMap) || !(actionRaw instanceof Map<?, ?> actionMap)) {
            return null;
        }
        DetailRule.DetailRuleWhen when = parseWhen((Map<String, Object>) whenMap);
        DetailRule.DetailRuleAction action = parseAction((Map<String, Object>) actionMap);
        if (when == null || action == null) {
            return null;
        }
        Object id = firstPresent(map, "id", "preset", "name");
        String ruleId = presetId != null ? presetId : (id == null ? null : String.valueOf(id));
        return new DetailRule(when, action, ruleId);
    }

    private static DetailRule.DetailRuleWhen parseWhen(Map<String, Object> when) {
        Object regionRaw = firstPresent(when, "region", "where", "scope");
        DetailRuleRegion region = DetailRuleRegion.parse(regionRaw == null ? null : String.valueOf(regionRaw));

        Object yRaw = firstPresent(when, "y", "y_anchor", "yAnchor", "height");
        DetailRuleYAnchor anchor = DetailRuleYAnchor.ABSOLUTE;
        int offset = 0;
        if (yRaw instanceof Number n) {
            offset = n.intValue();
        } else if (yRaw instanceof Map<?, ?> yMap) {
            Object anchorRaw = firstPresent((Map<String, Object>) yMap, "anchor", "ref", "datum");
            if (anchorRaw != null) {
                anchor = DetailRuleYAnchor.parse(String.valueOf(anchorRaw));
            }
            Object offRaw = firstPresent((Map<String, Object>) yMap, "offset", "delta");
            if (offRaw instanceof Number num) {
                offset = num.intValue();
            }
        } else if (yRaw != null) {
            String ys = String.valueOf(yRaw).trim();
            if (ys.matches("-?\\d+")) {
                offset = Integer.parseInt(ys);
            } else {
                anchor = DetailRuleYAnchor.parse(ys);
            }
        }

        Object filterRaw = firstPresent(when, "block", "block_filter", "blockFilter", "material");
        String blockFilter = filterRaw == null ? "wall" : String.valueOf(filterRaw);

        return new DetailRule.DetailRuleWhen(region, anchor, offset, blockFilter);
    }

    @SuppressWarnings("unchecked")
    private static DetailRule.DetailRuleAction parseAction(Map<String, Object> action) {
        Object typeRaw = firstPresent(action, "replace_with", "replaceWith", "type", "action");
        DetailRuleActionType type = DetailRuleActionType.parse(typeRaw == null ? null : String.valueOf(typeRaw));

        Object partRaw = firstPresent(action, "part", "semantic_part", "semanticPart");
        SemanticPart part = parseSemanticPart(partRaw == null ? null : String.valueOf(partRaw));

        Object blockRaw = firstPresent(action, "block", "block_id", "blockId");
        String blockId = blockRaw == null ? null : String.valueOf(blockRaw).trim();

        Object facingRaw = firstPresent(action, "facing", "orientation");
        DetailRuleFacing facing = DetailRuleFacing.parse(facingRaw == null ? null : String.valueOf(facingRaw));

        return new DetailRule.DetailRuleAction(type, part, blockId, facing);
    }

    private static SemanticPart parseSemanticPart(String raw) {
        if (raw == null || raw.isBlank()) {
            return SemanticPart.WALL_ACCENT;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return SemanticPart.valueOf(s);
        } catch (IllegalArgumentException e) {
            return SemanticPart.WALL_ACCENT;
        }
    }

    private static String ruleKey(DetailRule rule) {
        if (rule.presetId() != null && !rule.presetId().isBlank()) {
            return rule.presetId();
        }
        DetailRule.DetailRuleWhen w = rule.when();
        DetailRule.DetailRuleAction a = rule.action();
        return w.region() + "|" + w.yAnchor() + "|" + w.yOffset() + "|" + a.type();
    }

    private static Object firstPresent(Map<String, Object> map, String... keys) {
        if (map == null) {
            return null;
        }
        for (String key : keys) {
            Object v = map.get(key);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static boolean hasBasePlinthMarker(String profile) {
        if (profile == null) {
            return false;
        }
        return profile.toLowerCase(Locale.ROOT).contains("base_plinth");
    }

    private static boolean isEnabled(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v == null) {
            return false;
        }
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return "true".equals(s) || "yes".equals(s) || "on".equals(s);
    }

    private static boolean isDisabled(Object v) {
        if (v instanceof Boolean b) {
            return !b;
        }
        if (v == null) {
            return false;
        }
        return "false".equalsIgnoreCase(String.valueOf(v).trim());
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
