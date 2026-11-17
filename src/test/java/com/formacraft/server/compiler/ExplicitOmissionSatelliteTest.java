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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
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
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
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

    @Test
    void prepareComponentsDoesNotInferCrownWhenDesignRequirementOmitted() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "hall");
        params.put("roof_type", "flat");
        params.put("facade_profile", "classical_colonnade");
        Component mass = new Component(
                "MASS_MAIN",
                "main",
                new Vec3i(0, 0, 0),
                new Dimensions(20, 20, 12),
                List.of(),
                params);
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("DEFAULT")
                .components(List.of(mass))
                .proportionHints(Map.of("typology", "classical_monument"))
                .planMeta(Map.of(
                        "allow_compiler_satellites", true,
                        "design_requirements", Map.of(
                                "crown", "OMITTED",
                                "facade", "OMITTED",
                                "entrance", "OMITTED")))
                .build();

        var prepared = ComponentSatelliteInference.prepareComponents(plan, Map.of(), false);
        assertFalse(prepared.components().stream()
                .anyMatch(c -> c != null && "CROWN".equalsIgnoreCase(c.componentType())),
                "OMITTED crown must block typology-triggered crown inference");
    }

    @Test
    void inferredDefaultGableIsTaggedTechnicalDefault() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "house");
        Component mass = new Component(
                "MASS_MAIN",
                "main",
                new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 6),
                List.of(),
                params);
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("DEFAULT")
                .components(List.of(mass))
                .planMeta(Map.of("allow_compiler_satellites", true))
                .build();

        Component roof = ComponentSatelliteInference.makeRoofComponent(plan, mass, "main");
        assertTrue(roof != null);
        assertEquals("gable", String.valueOf(roof.params().get("roof_type")));
        @SuppressWarnings("unchecked")
        Map<String, Object> sources = (Map<String, Object>) roof.params().get("design_sources");
        assertTrue(sources != null);
        // resolveDefaultRoofType → gable is stamped COMPILER_INFERRED (not a silent untagged put).
        assertEquals("COMPILER_INFERRED", String.valueOf(sources.get("roof_type")));
    }

    @Test
    void inferredFacadeDefaultsAreTaggedTechnicalDefault() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "house");
        Component mass = new Component(
                "MASS_MAIN",
                "main",
                new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 6),
                List.of(),
                params);
        Component facade = ComponentSatelliteInference.makeFacadeComponent(mass, "main");
        assertEquals(0.25, ((Number) facade.params().get("window_ratio")).doubleValue(), 1e-9);
        assertEquals("regular", String.valueOf(facade.params().get("rhythm")));
        @SuppressWarnings("unchecked")
        Map<String, Object> sources = (Map<String, Object>) facade.params().get("design_sources");
        assertTrue(sources != null);
        assertEquals("TECHNICAL_DEFAULT", String.valueOf(sources.get("window_ratio")));
        assertEquals("TECHNICAL_DEFAULT", String.valueOf(sources.get("rhythm")));
    }

    @Test
    void inferredEntranceDefaultsAreTaggedTechnicalDefault() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "house");
        Component mass = new Component(
                "MASS_MAIN",
                "main",
                new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 6),
                List.of(),
                params);
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("DEFAULT")
                .components(List.of(mass))
                .build();
        Component entrance = ComponentSatelliteInference.makeEntranceComponent(
                plan, mass, "main", null);
        assertTrue(entrance != null);
        assertTrue(entrance.params().containsKey("door_width"));
        @SuppressWarnings("unchecked")
        Map<String, Object> sources = (Map<String, Object>) entrance.params().get("design_sources");
        assertTrue(sources != null);
        assertEquals("TECHNICAL_DEFAULT", String.valueOf(sources.get("door_width")));
        assertEquals("TECHNICAL_DEFAULT", String.valueOf(sources.get("door_height")));
        assertEquals("TECHNICAL_DEFAULT", String.valueOf(sources.get("canopy_depth")));
    }

    @Test
    void recoveredEmptyComponentsWallThicknessIsTaggedTechnicalDefault() {
        String json = """
                {"mode":"build","anchor":{"x":0,"y":64,"z":0},
                 "layout":{"slots":[{"slot_id":"main","anchor":{"x":0,"y":0,"z":0},
                   "facing":"SOUTH","size":{"w":12,"h":8,"d":10}}]},
                 "components":[]}
                """;
        LlmPlan plan;
        try {
            plan = com.formacraft.common.llm.parser.LlmPlanParser.parse(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertFalse(plan.allowCompilerSatellites(), "production JSON without key must be opt-in false");
        var prepared = ComponentSatelliteInference.prepareComponents(
                plan, Map.of("main", plan.layout().slots().getFirst()), false);
        Component mass = prepared.components().stream()
                .filter(c -> c != null && "MASS_MAIN".equalsIgnoreCase(c.componentType()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, ((Number) mass.params().get("wall_thickness")).intValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> sources = (Map<String, Object>) mass.params().get("design_sources");
        assertTrue(sources != null);
        assertEquals("TECHNICAL_DEFAULT", String.valueOf(sources.get("wall_thickness")));
        assertTrue(prepared.components().stream()
                .anyMatch(c -> c != null && "ROOF".equalsIgnoreCase(c.componentType())),
                "empty recovery must still transitional-infer roof");
    }

    @Test
    void missingWallThicknessFillIsTaggedTechnicalDefault() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "hall");
        Component mass = new Component(
                "MASS_MAIN",
                "main",
                new Vec3i(0, 0, 0),
                new Dimensions(16, 12, 8),
                List.of(),
                params);
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
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
        Component preparedMass = prepared.components().stream()
                .filter(c -> c != null && "MASS_MAIN".equalsIgnoreCase(c.componentType()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, ((Number) preparedMass.params().get("wall_thickness")).intValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> sources = (Map<String, Object>) preparedMass.params().get("design_sources");
        assertTrue(sources != null);
        assertEquals("TECHNICAL_DEFAULT", String.valueOf(sources.get("wall_thickness")));
    }
}
