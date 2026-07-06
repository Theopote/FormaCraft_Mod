package com.formacraft.common.proportion;

import com.formacraft.common.generation.component.util.RevolveProfileParser;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrownGrammarResolverTest {

    @Test
    void injectsProfileCurveFromProportionHints() {
        Map<String, Object> hints = Map.of(
                "profile_curve_y_radius", List.of(
                        Map.of("y_rel", 0.0, "radius", 0.5),
                        Map.of("y_rel", 0.5, "radius", 0.55),
                        Map.of("y_rel", 1.0, "radius", 0.1)
                )
        );
        LlmPlan plan = new LlmPlan(
                null, null, null, null, null,
                List.of(),
                null, null, hints, null,
                null, null, null, null, null, null, null, null, null
        );
        Component crown = new Component(
                "CROWN",
                "s0",
                null,
                new Dimensions(5, 5, 6),
                List.of(),
                Map.of()
        );

        Component enriched = CrownGrammarResolver.apply(plan, crown);
        assertNotNull(RevolveProfileParser.resolve(enriched.params()));
        assertTrue(enriched.params().containsKey("profile_curve_y_radius"));
    }
}
