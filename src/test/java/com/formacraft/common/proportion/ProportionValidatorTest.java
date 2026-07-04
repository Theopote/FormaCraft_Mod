package com.formacraft.common.proportion;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProportionValidatorTest {

    @Test
    void resolveForPrompt_cottage() {
        ProportionCardRegistry.ProportionCard card = ProportionCardRegistry.resolveForPrompt(
                "盖一个 7x7 的小石头房子，带门和窗");
        assertNotNull(card);
        assertTrue("cottage_refined".equals(card.id()) || "cottage".equals(card.typology()));
    }

    @Test
    void resolveForPrompt_castle() {
        ProportionCardRegistry.ProportionCard card = ProportionCardRegistry.resolveForPrompt(
                "盖一座中世纪石头城堡，有塔楼和城墙");
        assertNotNull(card);
        assertTrue("castle_wall".equals(card.id()));
    }

    @Test
    void validatePlan_cottageHints() {
        ProportionCardRegistry.ProportionCard card = ProportionCardRegistry.getById("cottage_refined");
        assertNotNull(card);

        Map<String, Object> plan = new LinkedHashMap<>();
        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("height_to_width", 0.65);
        hints.put("window_wall_ratio", 0.2);
        plan.put("proportion_hints", hints);

        Map<String, Object> mass = new LinkedHashMap<>();
        mass.put("component_type", "MASS_MAIN");
        Map<String, Object> dims = new LinkedHashMap<>();
        dims.put("width", 7);
        dims.put("depth", 7);
        dims.put("height", 5);
        mass.put("dimensions", dims);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("void_ratio", 0.0);
        mass.put("params", params);
        plan.put("components", List.of(mass));

        List<ProportionValidator.CheckResult> results = ProportionValidator.validatePlan(plan, card);
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(r -> "has_proportion_hints".equals(r.name()) && r.passed()));
        assertTrue(results.stream().anyMatch(r -> "derived_height_to_width".equals(r.name()) && r.passed()));
    }

    @Test
    void promptBlock_nonEmpty() {
        ProportionCardRegistry.ProportionCard card = ProportionCardRegistry.getById("castle_wall");
        assertNotNull(card);
        String block = ProportionCardRegistry.promptBlockForCard(card);
        assertTrue(block.contains("PROPORTION ONTOLOGY"));
        assertTrue(block.contains("proportion_hints"));
    }
}
