package com.formacraft.common.proportion;

import com.formacraft.common.llm.dto.LlmPlanTestFixtures;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoofGrammarResolverTest {

    @Test
    void globalMansardDormerHintDoesNotInventDormersWithoutLocalCue() {
        Component roof = new Component(
                "ROOF",
                "s0",
                new Vec3i(0, 12, 0),
                new Dimensions(15, 13, 4),
                List.of("overhang"),
                Map.of("roof_height", 4, "roof_type", "mansard")
        );
        LlmPlan plan = planWithHints(Map.of(
                "typology", "baroque_townhouse",
                "roof_specialty", "mansard_dormer"
        ));
        Component enriched = RoofGrammarResolver.apply(plan, roof);
        assertEquals("mansard", enriched.params().get("roof_type"));
        assertNotEquals(true, enriched.params().get("roof_dormers"));
        assertFalseSpecialtyDormer(enriched);
    }

    @Test
    void localDormerCueStillResolvesMansardDormer() {
        Map<String, Object> params = new HashMap<>();
        params.put("roof_height", 4);
        params.put("roof_type", "mansard");
        params.put("roof_dormers", true);
        Component roof = new Component(
                "ROOF", "s0", new Vec3i(0, 12, 0),
                new Dimensions(15, 13, 4), List.of(), params);
        LlmPlan plan = planWithHints(Map.of("typology", "baroque_townhouse"));
        Component enriched = RoofGrammarResolver.apply(plan, roof);
        assertEquals("mansard", enriched.params().get("roof_type"));
        assertTrue(Boolean.TRUE.equals(enriched.params().get("roof_dormers")));
        assertTrue(String.valueOf(enriched.params().get("roof_specialty")).toLowerCase().contains("dormer"));
    }

    @Test
    void siblingMansardWithoutDormersKeepsNoDormersAfterGrammar() {
        Component annexRoof = new Component(
                "ROOF", "annex", new Vec3i(30, 8, 0),
                new Dimensions(10, 10, 4), List.of(),
                Map.of("component_id", "annex_roof", "host_id", "french_wing", "roof_type", "mansard"));
        LlmPlan plan = planWithHints(Map.of(
                "typology", "baroque_mansion",
                "roof_specialty", "mansard_dormer"
        ));
        Component enriched = RoofGrammarResolver.apply(plan, annexRoof);
        assertEquals("mansard", enriched.params().get("roof_type"));
        assertNotEquals(true, enriched.params().get("roof_dormers"));
        assertFalseSpecialtyDormer(enriched);
    }

    private static void assertFalseSpecialtyDormer(Component roof) {
        Object specialty = roof.params().get("roof_specialty");
        if (specialty == null) return;
        org.junit.jupiter.api.Assertions.assertFalse(
                String.valueOf(specialty).toLowerCase().contains("dormer"));
    }

    private static LlmPlan planWithHints(Map<String, Object> hints) {
        return LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .mode(LlmPlan.Mode.build)
                .styleProfile("DEFAULT")
                .anchor(new Vec3i(0, 64, 0))
                .globalConstraints(new GlobalConstraints(
                        GlobalConstraints.Facing.SOUTH,
                        GlobalConstraints.Symmetry.NONE,
                        GlobalConstraints.TerrainStrategy.ADAPTIVE))
                .layout(new Layout(null, false, List.of()))
                .components(List.of())
                .proportionHints(hints)
                .build();

    }
}
