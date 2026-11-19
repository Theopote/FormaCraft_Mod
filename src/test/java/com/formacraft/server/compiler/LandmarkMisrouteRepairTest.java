package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LandmarkMisrouteRepairTest {

    @Test
    void assemblyEiffelWithAuthoredHeightEmitsGapAndKeepsDimensions() {
        Map<String, Object> ringParams = new HashMap<>();
        ringParams.put("component_id", "office_ring");
        ringParams.put("fbd_program", Map.of("kind", "ring"));
        Component ring = new Component(
                "ASSEMBLY", "landmark", new Vec3i(0, 0, 0),
                new Dimensions(40, 40, 100), List.of("eiffel", "埃菲尔"),
                ringParams);
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .planMeta(Map.of("request_intent_text", "建造一座100格高的埃菲尔铁塔"))
                .components(List.of(ring))
                .build();

        LlmPlan out = LandmarkMisrouteRepair.apply(plan);
        assertTrue(out.hasCapabilityGap());
        assertEquals("E_LANDMARK_ROUTE_UNSUPPORTED", out.capabilityGap().code());
        assertEquals(1, out.components().size());
        assertEquals("ASSEMBLY", out.components().getFirst().componentType());
        assertEquals(100, out.components().getFirst().dimensions().height());
        assertEquals(40, out.components().getFirst().dimensions().width());
    }

    @Test
    void alreadyRoutedEiffelStructureUnchanged() {
        Component structure = new Component(
                "STRUCTURE", "landmark", new Vec3i(0, 0, 0),
                new Dimensions(27, 27, 60), List.of("landmark:eiffel_tower"),
                Map.of("module_id", "eiffel_tower", "component_id", "eiffel"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .planMeta(Map.of("request_intent_text", "埃菲尔铁塔"))
                .components(List.of(structure))
                .build();
        assertSame(plan, LandmarkMisrouteRepair.apply(plan));
    }

    @Test
    void moduleTextWithMisroutedAssemblyStillGaps() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "office_ring");
        params.put("fbd_program", Map.of("kind", "ring"));
        Component ring = new Component(
                "ASSEMBLY", "landmark", new Vec3i(0, 0, 0),
                new Dimensions(40, 40, 100), List.of(),
                params);
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .planMeta(Map.of("request_intent_text", "module:eiffel_tower 埃菲尔铁塔"))
                .distinguishingFeatures(List.of("eiffel"))
                .components(List.of(ring))
                .build();
        LlmPlan out = LandmarkMisrouteRepair.apply(plan);
        assertTrue(out.hasCapabilityGap(),
                "module: text without STRUCTURE executor must not skip misroute gap");
        assertEquals(100, out.components().getFirst().dimensions().height());
    }

    @Test
    void landmarkFeatureTagOnAssemblyDoesNotCountAsRouted() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "office_ring");
        params.put("fbd_program", Map.of("kind", "ring"));
        Component ring = new Component(
                "ASSEMBLY", "landmark", new Vec3i(0, 0, 0),
                new Dimensions(40, 40, 100), List.of("landmark:eiffel_tower"),
                params);
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .planMeta(Map.of("request_intent_text", "建造埃菲尔铁塔"))
                .components(List.of(ring))
                .build();
        LlmPlan out = LandmarkMisrouteRepair.apply(plan);
        assertTrue(out.hasCapabilityGap(),
                "landmark: feature tag is semantic only — not a STRUCTURE executor");
        assertEquals("ASSEMBLY", out.components().getFirst().componentType());
    }
}
