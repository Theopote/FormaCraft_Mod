package com.formacraft.common.detail;

import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.LlmPlan;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetailRuleParserTest {

    @Test
    void resolvesExplicitDetailRules() {
        Map<String, Object> hints = new HashMap<>();
        hints.put("detail_rules", List.of(
                Map.of(
                        "when", Map.of("region", "perimeter", "y", "roof_eave"),
                        "action", Map.of("replace_with", "slab", "part", "WALL_ACCENT")
                )
        ));
        LlmPlan plan = planWithHints(hints);
        List<DetailRule> rules = DetailRuleParser.resolve(plan);
        assertEquals(1, rules.size());
        assertEquals(DetailRuleYAnchor.ROOF_EAVE, rules.get(0).when().yAnchor());
        assertEquals(DetailRuleActionType.SLAB, rules.get(0).action().type());
    }

    @Test
    void injectsFloorCornicePresetWhenFlagSet() {
        Map<String, Object> hints = Map.of("floor_cornice", true);
        LlmPlan plan = planWithHints(hints);
        List<DetailRule> rules = DetailRuleParser.resolve(plan);
        assertFalse(rules.isEmpty());
        assertTrue(rules.stream().anyMatch(r -> DetailRuleParser.PRESET_FLOOR_CORNICE.equals(r.presetId())));
    }

    @Test
    void deduplicatesExplicitRuleAndPreset() {
        Map<String, Object> hints = new HashMap<>();
        hints.put("floor_cornice", true);
        hints.put("detail_rules", List.of(
                Map.of(
                        "id", DetailRuleParser.PRESET_FLOOR_CORNICE,
                        "when", Map.of("region", "layer_edge", "y", "floor_boundary"),
                        "action", Map.of("replace_with", "inverted_stairs", "facing", "outward")
                )
        ));
        LlmPlan plan = planWithHints(hints);
        List<DetailRule> rules = DetailRuleParser.resolve(plan);
        long corniceRules = rules.stream()
                .filter(r -> r.action().type() == DetailRuleActionType.INVERTED_STAIRS
                        && r.when().yAnchor() == DetailRuleYAnchor.FLOOR_BOUNDARY)
                .count();
        assertEquals(1, corniceRules);
    }

    @Test
    void parsesNumericYAsAbsoluteOffset() {
        Map<String, Object> hints = Map.of(
                "detail_rules", List.of(
                        Map.of(
                                "when", Map.of("region", "perimeter", "y", 2),
                                "action", Map.of("replace_with", "block", "block", "minecraft:quartz_block")
                        )
                )
        );
        LlmPlan plan = planWithHints(hints);
        DetailRule rule = DetailRuleParser.resolve(plan).get(0);
        assertEquals(DetailRuleYAnchor.ABSOLUTE, rule.when().yAnchor());
        assertEquals(2, rule.when().yOffset());
    }

    private static LlmPlan planWithHints(Map<String, Object> hints) {
        return new LlmPlan(
                null, null, null, null,
                new Layout(null, false, List.of()),
                List.of(), null, null,
                hints, null, null, null, null, null, null, null, null, null, null
        );
    }
}
