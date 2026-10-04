package com.formacraft.common.style;

import com.formacraft.common.llm.dto.*;
import com.formacraft.common.proportion.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExplicitDesignPolicyTest {
    @Test void noneIsPreservedAndComplexStyleFeaturesAreNotAdded() {
        var c = new Component("MASS_MAIN", "s", new Vec3i(0, 0, 0), new Dimensions(15, 13, 11), List.of(),
                Map.of("plan_type", "none", "roof_type", "none", "no_complex_decor", true));
        var output = StyleIntentResolver.apply(LlmPlanTestFixtures.builder().styleProfile("GOTHIC").build(), c);
        assertEquals("none", output.params().get("roof_type"));
        assertEquals("none", output.params().get("plan_type"));
        assertTrue(output.features().isEmpty());
    }
    @Test void explicitRoofTypeAndDormerOptOutSurviveProportionHints() {
        var c = new Component("ROOF", "s", new Vec3i(0, 10, 0), new Dimensions(15, 13, 4), List.of(),
                Map.of("roof_type", "gable", "roof_dormers", false));
        var plan = LlmPlanTestFixtures.builder().proportionHints(Map.of("roof_specialty", "mansard_dormer")).build();
        var output = RoofGrammarResolver.apply(plan, c);
        assertEquals("gable", output.params().get("roof_type"));
        assertEquals(false, output.params().get("roof_dormers"));
        var disabled = new Component("ROOF", "s", c.relativePosition(), c.dimensions(), List.of(), Map.of("roof_type", "none"));
        assertSame(disabled, RoofGrammarResolver.apply(plan, disabled));
    }
    @Test void zeroWindowRatioIsNotClampedUpByProportionCard() {
        var c = new Component("MASS_MAIN", "s", new Vec3i(0, 0, 0), new Dimensions(15, 13, 11), List.of(), Map.of("window_ratio", 0.0));
        assertSame(c, OpeningGrammarResolver.apply(LlmPlanTestFixtures.builder().proportionHints(
                Map.of("window_wall_ratio", 0.5, "typology", "baroque_townhouse")).build(), c));
    }
}
