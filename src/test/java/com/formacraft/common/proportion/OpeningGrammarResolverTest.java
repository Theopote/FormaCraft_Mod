package com.formacraft.common.proportion;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpeningGrammarResolverTest {

    @Test
    void injectsWindowAspectFromProportionHints() {
        Map<String, Object> hints = Map.of(
                "typology", "cottage",
                "window_aspect", "square");
        LlmPlan plan = planWithHints(hints);
        Component facade = facadeWithoutAspect();

        Component enriched = OpeningGrammarResolver.apply(plan, facade);
        assertEquals("square", enriched.params().get("window_aspect"));
    }

    @Test
    void fallsBackToCardOpeningGrammar() {
        Map<String, Object> hints = Map.of("typology", "medieval_castle");
        LlmPlan plan = planWithHints(hints);
        Component facade = facadeWithoutAspect();

        Component enriched = OpeningGrammarResolver.apply(plan, facade);
        assertEquals("vertical_strip", enriched.params().get("window_aspect"));
    }

    @Test
    void clampsInvalidAspectToCardAllowed() {
        Map<String, Object> hints = Map.of("typology", "cottage");
        LlmPlan plan = planWithHints(hints);
        Component facade = facadeWithAspect("ribbon_glazing");

        Component enriched = OpeningGrammarResolver.apply(plan, facade);
        assertEquals("square", enriched.params().get("window_aspect"));
    }

    private static LlmPlan planWithHints(Map<String, Object> hints) {
        return new LlmPlan(
                LlmPlan.Mode.build,
                "DEFAULT",
                new Vec3i(0, 64, 0),
                new GlobalConstraints(GlobalConstraints.Facing.SOUTH, GlobalConstraints.Symmetry.NONE,
                        GlobalConstraints.TerrainStrategy.ADAPTIVE),
                new Layout(null, false, List.of()),
                List.of(),
                null,
                null,
                hints,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static Component facadeWithoutAspect() {
        return new Component(
                "FACADE_WINDOWS",
                null,
                new Vec3i(0, 0, 0),
                new Dimensions(7, 1, 5),
                List.of(),
                new HashMap<>()
        );
    }

    private static Component facadeWithAspect(String aspect) {
        Map<String, Object> params = new HashMap<>();
        params.put("window_aspect", aspect);
        return new Component(
                "FACADE_WINDOWS",
                null,
                new Vec3i(0, 0, 0),
                new Dimensions(7, 1, 5),
                List.of(),
                params
        );
    }
}
