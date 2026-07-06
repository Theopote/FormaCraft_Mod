package com.formacraft.common.proportion;

import com.formacraft.common.generation.component.util.CrownTemplateLibrary;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CrownGrammarResolverTest {

    @Test
    void injectsClassicalCupolaForMonumentTypology() {
        Component crown = new Component(
                "CROWN",
                "s0",
                new Vec3i(0, 12, 0),
                new Dimensions(5, 5, 6),
                List.of("crown"),
                Map.of()
        );
        LlmPlan plan = planWithHints(Map.of("typology", "classical_monument"));
        Component enriched = CrownGrammarResolver.apply(plan, crown);
        assertEquals(CrownTemplateLibrary.CLASSICAL_CUPOLA, enriched.params().get("crown_template"));
    }

    private static LlmPlan planWithHints(Map<String, Object> hints) {
        return new LlmPlan(
                LlmPlan.Mode.build,
                "DEFAULT",
                new Vec3i(0, 64, 0),
                new GlobalConstraints(
                        GlobalConstraints.Facing.SOUTH,
                        GlobalConstraints.Symmetry.NONE,
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
}
