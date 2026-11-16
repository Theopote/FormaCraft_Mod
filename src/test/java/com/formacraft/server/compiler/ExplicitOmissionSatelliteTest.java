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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicit MASS opt-outs must block compiler satellite injection even when
 * {@code allow_compiler_satellites=true}.
 */
class ExplicitOmissionSatelliteTest {

    @Test
    void prepareComponentsDoesNotInferRoofWhenRoofTypeNone() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "hall");
        params.put("roof_type", "none");
        params.put("design_sources", Map.of("roof_type", "USER_EXPLICIT"));
        Component mass = new Component(
                "MASS_MAIN",
                "main",
                new Vec3i(0, 0, 0),
                new Dimensions(16, 12, 8),
                List.of(),
                params);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .styleProfile("DEFAULT")
                .components(List.of(mass))
                .planMeta(Map.of(
                        "allow_compiler_satellites", true,
                        "design_requirements", Map.of(
                                "roof", "OMITTED",
                                "facade", "OMITTED",
                                "entrance", "OMITTED",
                                "crown", "OMITTED")))
                .build();

        var prepared = ComponentSatelliteInference.prepareComponents(plan, Map.of(), false);
        assertFalse(prepared.components().stream()
                .anyMatch(c -> c != null && "ROOF".equalsIgnoreCase(c.componentType())));
    }

    @Test
    void prepareComponentsDoesNotInferWindowsOrEntranceWhenDisabled() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "hall");
        params.put("roof_type", "flat");
        params.put("window_style", "none");
        params.put("window_ratio", 0);
        params.put("entrance_type", "none");
        params.put("design_sources", Map.of(
                "window_style", "USER_EXPLICIT",
                "window_ratio", "USER_EXPLICIT",
                "entrance_type", "USER_EXPLICIT"));
        Component mass = new Component(
                "MASS_MAIN",
                "main",
                new Vec3i(0, 0, 0),
                new Dimensions(16, 12, 8),
                List.of(),
                params);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .styleProfile("DEFAULT")
                .components(List.of(mass))
                .planMeta(Map.of(
                        "allow_compiler_satellites", true,
                        "design_requirements", Map.of(
                                "facade", "OMITTED",
                                "entrance", "OMITTED",
                                "crown", "OMITTED")))
                .build();

        var prepared = ComponentSatelliteInference.prepareComponents(plan, Map.of(), false);
        assertFalse(prepared.components().stream()
                .anyMatch(c -> c != null && "FACADE_WINDOWS".equalsIgnoreCase(c.componentType())));
        assertFalse(prepared.components().stream()
                .anyMatch(c -> c != null && "ENTRANCE".equalsIgnoreCase(c.componentType())));
        assertTrue(prepared.components().stream()
                .anyMatch(c -> c != null && "MASS_MAIN".equalsIgnoreCase(c.componentType())));
    }
}
