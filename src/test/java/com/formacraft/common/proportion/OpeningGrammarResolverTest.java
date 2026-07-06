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

    @Test
    void injectsDefaultRepeatingPatternForClassicalTypology() {
        Map<String, Object> hints = Map.of("typology", "classical_monument");
        LlmPlan plan = planWithHints(hints);
        Component facade = facadeWithoutAspect();

        Component enriched = OpeningGrammarResolver.apply(plan, facade);
        assertEquals(5, ((Number) ((Map<?, ?>) enriched.params().get("repeating_pattern")).get("unit_width_z")).intValue());
        assertEquals("base_plinth,vertical_pilasters", enriched.params().get("facade_profile"));
    }

    @Test
    void injectsExplicitRepeatingPatternFromHints() {
        Map<String, Object> pattern = Map.of(
                "unit_width_z", 4,
                "elements", List.of(
                        Map.of("type", "pillar", "width", 1),
                        Map.of("type", "window", "width", 2),
                        Map.of("type", "pillar", "width", 1)
                )
        );
        Map<String, Object> hints = Map.of("typology", "cottage", "repeating_pattern", pattern);
        LlmPlan plan = planWithHints(hints);
        Component mass = new Component(
                "MASS_MAIN",
                null,
                new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 8),
                List.of(),
                new HashMap<>()
        );

        Component enriched = OpeningGrammarResolver.apply(plan, mass);
        assertEquals(4, ((Number) ((Map<?, ?>) enriched.params().get("repeating_pattern")).get("unit_width_z")).intValue());
    }

    @Test
    void injectsRhythmPresetForClassicalTypology() {
        Map<String, Object> hints = Map.of("typology", "classical_monument");
        LlmPlan plan = planWithHints(hints);
        Component facade = facadeWithoutAspect();

        Component enriched = OpeningGrammarResolver.apply(plan, facade);
        assertEquals("CLASSICAL_PILASTER_BAY", enriched.params().get("rhythm_preset"));
        assertEquals("full", enriched.params().get("window_order"));
    }

    @Test
    void clampsVoidRatioToCardMax() {
        Map<String, Object> hints = Map.of("typology", "cottage");
        LlmPlan plan = planWithHints(hints);
        Map<String, Object> params = new HashMap<>();
        params.put("void_ratio", 0.45);
        Component mass = new Component(
                "MASS_MAIN",
                null,
                new Vec3i(0, 0, 0),
                new Dimensions(7, 7, 5),
                List.of(),
                params
        );

        Component enriched = OpeningGrammarResolver.apply(plan, mass);
        assertEquals(0.12, ((Number) enriched.params().get("void_ratio")).doubleValue(), 1e-6);
    }

    @Test
    void clampsWindowRatioToCardMax() {
        Map<String, Object> hints = Map.of("typology", "cottage");
        LlmPlan plan = planWithHints(hints);
        Map<String, Object> params = new HashMap<>();
        params.put("window_ratio", 0.9);
        Component facade = new Component(
                "FACADE_WINDOWS",
                null,
                new Vec3i(0, 0, 0),
                new Dimensions(7, 1, 5),
                List.of(),
                params
        );

        Component enriched = OpeningGrammarResolver.apply(plan, facade);
        assertEquals(0.38, ((Number) enriched.params().get("window_ratio")).doubleValue(), 1e-6);
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
                null,
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
