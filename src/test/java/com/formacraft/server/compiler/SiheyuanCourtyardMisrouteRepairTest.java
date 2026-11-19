package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SiheyuanCourtyardMisrouteRepairTest {

    @Test
    void threeJinSiheyuanKeepsCompositionalPlan() {
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("Chinese_Vernacular_Siheyuan")
                .planMeta(Map.of(
                        "request_intent_text",
                        "生成一座三进四合院，正房、东西厢房、倒座齐全，院落可走通，正房高于厢房，白墙灰瓦。"))
                .components(List.of(
                        mass("north_hall", -8, -18, 16, 6, 6),
                        mass("east_wing", 8, -10, 6, 14, 4),
                        mass("west_wing", -14, -10, 6, 14, 4),
                        mass("dao_zuo", -6, 8, 12, 5, 4),
                        mass("extra_shed", 20, 20, 8, 8, 3)))
                .build();
        LlmPlan out = SiheyuanCourtyardMisrouteRepair.apply(plan);
        assertSame(plan, out, "三进 must not be silently demoted to single courtyard_compound");
        assertTrue(out.components().size() >= 4, "compositional masses must remain");
    }

    @Test
    void incompleteSingleJinWithoutCourtEmitsGapAndKeepsMasses() {
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("Chinese_Vernacular_Siheyuan")
                .planMeta(Map.of("request_intent_text", "生成一座四合院，正房和厢房"))
                .components(List.of(
                        mass("main", 0, 0, 12, 8, 6),
                        mass("east", 14, 0, 6, 10, 4),
                        mass("west", -8, 0, 6, 10, 4)))
                .build();
        LlmPlan out = SiheyuanCourtyardMisrouteRepair.apply(plan);
        assertTrue(out.hasCapabilityGap(), "open composition must not be replaced by typology STRUCTURE");
        assertEquals("E_SIHEYUAN_COMPOSITION_UNSUPPORTED", out.capabilityGap().code());
        assertEquals(3, out.components().size(), "AI masses must be retained");
        assertTrue(out.components().stream().noneMatch(c ->
                "STRUCTURE".equalsIgnoreCase(c.componentType())
                        && c.params() != null
                        && "courtyard_compound".equals(String.valueOf(c.params().get("typology_id")))));
    }

    @Test
    void nonKeywordMassIdsStillRefuseSilentStructureRewrite() {
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("Chinese_Vernacular_Siheyuan")
                .planMeta(Map.of("request_intent_text", "生成一座四合院"))
                .components(List.of(
                        mass("a_block", 0, 0, 12, 8, 6),
                        mass("b_block", 14, 0, 6, 10, 4),
                        mass("c_block", -8, 0, 6, 10, 4),
                        mass("d_block", 0, 12, 10, 5, 4)))
                .build();
        LlmPlan out = SiheyuanCourtyardMisrouteRepair.apply(plan);
        assertTrue(out.hasCapabilityGap());
        assertEquals(4, out.components().size());
        assertTrue(out.components().stream().allMatch(c ->
                c.componentType() != null && c.componentType().startsWith("MASS")));
    }

    @Test
    void facingPreservedOnGapWithoutStructureRewrite() {
        Component main = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(12, 8, 6), List.of(),
                Map.of("component_id", "main", "facing", "NORTH", "anchor_mode", "min_corner"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("Chinese_Vernacular_Siheyuan")
                .planMeta(Map.of("request_intent_text", "生成一座朝北的四合院，正房和厢房"))
                .components(List.of(
                        main,
                        mass("east", 14, 0, 6, 10, 4),
                        mass("west", -8, 0, 6, 10, 4)))
                .build();
        LlmPlan out = SiheyuanCourtyardMisrouteRepair.apply(plan);
        assertTrue(out.hasCapabilityGap());
        Component kept = out.components().stream()
                .filter(c -> "main".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals("NORTH", String.valueOf(kept.params().get("facing")));
    }

    @Test
    void leavesExistingCourtyardCompoundAlone() {
        Component structure = new Component(
                "STRUCTURE", "main", new Vec3i(0, 0, 0),
                new Dimensions(36, 44, 10), List.of("typology:courtyard_compound"),
                Map.of("typology_id", "courtyard_compound", "component_id", "yard"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("Chinese_Vernacular_Siheyuan")
                .planMeta(Map.of("request_intent_text", "生成一座四合院"))
                .components(List.of(structure))
                .build();
        LlmPlan out = SiheyuanCourtyardMisrouteRepair.apply(plan);
        assertSame(plan, out);
    }

    @Test
    void explicitTypologyOptInLeavesMultiMassAlone() {
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("Chinese_Vernacular_Siheyuan")
                .planMeta(Map.of("request_intent_text",
                        "typology:courtyard_compound 生成一座四合院，正房和厢房"))
                .components(List.of(
                        mass("main", 0, 0, 12, 8, 6),
                        mass("east", 14, 0, 6, 10, 4),
                        mass("west", -8, 0, 6, 10, 4)))
                .build();
        LlmPlan out = SiheyuanCourtyardMisrouteRepair.apply(plan);
        assertSame(plan, out, "explicit typology opt-in must not force gap or STRUCTURE rewrite");
    }

    private static Component mass(String id, int x, int z, int w, int d, int h) {
        return new Component(
                "MASS_MAIN", "main", new Vec3i(x, 0, z),
                new Dimensions(w, d, h), List.of(),
                Map.of("component_id", id, "anchor_mode", "min_corner"));
    }
}
