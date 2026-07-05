package com.formacraft.common.proportion;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoofGrammarResolverTest {

    @Test
    void injectsMansardAndDormersForBaroqueTypology() {
        Component roof = new Component(
                "ROOF",
                "s0",
                new Vec3i(0, 12, 0),
                new Dimensions(15, 13, 4),
                List.of("overhang"),
                Map.of("roof_height", 4)
        );
        LlmPlan plan = planWithHints(Map.of(
                "typology", "baroque_townhouse",
                "roof_specialty", "mansard_dormer"
        ));
        Component enriched = RoofGrammarResolver.apply(plan, roof);
        assertEquals("mansard", enriched.params().get("roof_type"));
        assertTrue(Boolean.TRUE.equals(enriched.params().get("roof_dormers"))
                || "true".equalsIgnoreCase(String.valueOf(enriched.params().get("roof_dormers"))));
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
                null
        );
    }
}
