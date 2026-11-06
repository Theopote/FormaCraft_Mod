package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RoofIntentNegationRepairTest {

    @Test
    void doNotMakeXieshanDoesNotRequireXieshan() {
        LlmPlan plan = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder()
                        .components(List.of(mass(), gableRoof()))
                        .build(),
                "生成一座徽派民居，马头墙，白墙黑瓦，双坡屋顶，天井院落，不要做成歇山大殿。");
        // 双坡 is the positive roof; 歇山 is forbidden — must not force xieshan.
        assertEquals("gable", PlanSemanticValidator.explicitRoofType(plan, null));
        var gap = PlanSemanticValidator.check(plan);
        assertTrue(gap.isEmpty(), () -> "expected empty check, got " + gap.orElse(null));
    }

    @Test
    void pythonAuthoredXieshanForbidIsClearedByEnsure() {
        // Simulates backend stamping roof_type=xieshan despite 「不要做成歇山」.
        LlmPlan seeded = LlmPlanTestFixtures.builder()
                .components(List.of(mass(), gableRoof()))
                .planMeta(Map.of(
                        "request_intent", Map.of(
                                "schema", "formacraft.request_intent.v1",
                                "original_text",
                                "生成一座徽派民居，马头墙，白墙黑瓦，双坡屋顶，天井院落，不要做成歇山大殿。",
                                "building_class", "temple",
                                "confidence", 0.9,
                                "roof_type", "xieshan",
                                "roof_explicit", true,
                                "roof_forbids", List.of())))
                .build();
        LlmPlan plan = RequestIntentInjector.ensure(seeded, (String) null);
        assertEquals("gable", PlanSemanticValidator.explicitRoofType(plan, null));
        assertTrue(PlanSemanticValidator.check(plan).isEmpty());
    }

    @Test
    void doubleEaveXieshanForcesPrimaryRoofParams() {
        Component hall = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(16, 12, 10), List.of(),
                Map.of("component_id", "main_hall", "anchor_mode", "min_corner"));
        Component roof = new Component(
                "ROOF", "main", new Vec3i(0, 10, 0),
                new Dimensions(18, 14, 4), List.of(),
                Map.of("component_id", "main_roof", "host_id", "main_hall",
                        "roof_type", "gable", "anchor_mode", "min_corner"));
        LlmPlan plan = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder().components(List.of(hall, roof)).build(),
                "生成一座重檐歇山顶大雄宝殿，主殿必须是重檐歇山。");
        LlmPlan repaired = PlanIntentFidelityRepair.apply(plan);
        Component outRoof = repaired.components().stream()
                .filter(c -> "ROOF".equalsIgnoreCase(c.componentType()))
                .findFirst().orElseThrow();
        assertEquals("xieshan", String.valueOf(outRoof.params().get("roof_type")));
        assertEquals(Boolean.TRUE, outRoof.params().get("double_eave"));
        assertTrue(PlanSemanticValidator.check(repaired).isEmpty());
    }

    @Test
    void compoundCourtyardKeepsPrimaryXieshan() {
        Component hall = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(14, 10, 8), List.of(),
                Map.of("component_id", "main_hall", "anchor_mode", "min_corner"));
        Component roof = new Component(
                "ROOF", "main", new Vec3i(0, 8, 0),
                new Dimensions(16, 12, 3), List.of(),
                Map.of("component_id", "main_roof", "host_id", "main_hall",
                        "roof_type", "hip", "anchor_mode", "min_corner"));
        LlmPlan plan = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder().components(List.of(hall, roof)).build(),
                "生成一个小院落：主屋歇山顶白墙黑瓦，东侧一座攒尖六角亭，西侧一座硬山厢房，三者屋顶类型不要串。");
        LlmPlan repaired = PlanIntentFidelityRepair.apply(plan);
        assertEquals("xieshan", String.valueOf(repaired.components().stream()
                .filter(c -> "ROOF".equalsIgnoreCase(c.componentType()))
                .findFirst().orElseThrow().params().get("roof_type")));
        Optional<?> gap = PlanSemanticValidator.check(repaired);
        assertTrue(gap.isEmpty(), () -> String.valueOf(gap.orElse(null)));
    }

    private static Component mass() {
        return new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 7), List.of(),
                Map.of("component_id", "hall", "anchor_mode", "min_corner"));
    }

    private static Component gableRoof() {
        return new Component(
                "ROOF", "main", new Vec3i(0, 7, 0),
                new Dimensions(14, 12, 3), List.of(),
                Map.of("component_id", "roof", "host_id", "hall",
                        "roof_type", "gable", "anchor_mode", "min_corner"));
    }
}
