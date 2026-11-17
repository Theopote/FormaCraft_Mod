package com.formacraft.server.compiler;

import com.formacraft.common.generation.design.DesignRequirement;
import com.formacraft.common.generation.design.DesignRequirementAccess;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Strict-mode (allow_compiler_satellites=false) integrity: provenance-gated omissions,
 * and multi-mass roof authorship surviving prepare → BlockPatch.
 */
class StrictModeDesignScopeIntegrityTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @AfterEach
    void clearDiagnostics() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void aiPlannedRoofNoneStillRequiresRoofUnderPlanRequired() {
        Component mass = mass("house", Map.of(
                "component_id", "house",
                "roof_type", "none",
                "design_sources", Map.of("roof_type", "AI_PLANNED")));
        LlmPlan plan = strictPlan(List.of(mass), Map.of(
                "roof", "REQUIRED",
                "facade", "OMITTED",
                "entrance", "OMITTED",
                "crown", "OMITTED"));

        assertEquals(DesignRequirement.REQUIRED, DesignRequirementAccess.roof(plan, mass));
        var gap = CompilerSatellitePolicy.missingSatellitesGap(
                plan, mass, true, true, false, false, true, false);
        assertTrue(gap.isPresent());
        assertEquals("E_DESIGN_INCOMPLETE", gap.get().code());
        assertTrue(gap.get().message().contains("ROOF"));
    }

    @Test
    void userExplicitRoofNoneBeatsPlanRequiredInStrictMode() {
        Component mass = mass("open_court", Map.of(
                "component_id", "open_court",
                "roof_type", "none",
                "roof_requirement", "OMITTED",
                "design_sources", Map.of(
                        "roof_type", "USER_EXPLICIT",
                        "roof_requirement", "USER_EXPLICIT")));
        LlmPlan plan = strictPlan(List.of(mass), Map.of(
                "roof", "REQUIRED",
                "facade", "OMITTED",
                "entrance", "OMITTED",
                "crown", "OMITTED"));

        assertEquals(DesignRequirement.OMITTED, DesignRequirementAccess.roof(plan, mass));
        assertTrue(CompilerSatellitePolicy.missingSatellitesGap(
                plan, mass, true, true, false, false, true, false).isEmpty());
    }

    @Test
    void prepareComponentsFailsWhenAiOmitsRoofAgainstPlanRequired() {
        Component mass = mass("hall", Map.of(
                "component_id", "hall",
                "roof_type", "none",
                "design_sources", Map.of("roof_type", "AI_PLANNED")));
        LlmPlan plan = strictPlan(List.of(mass), Map.of(
                "roof", "REQUIRED",
                "facade", "OMITTED",
                "entrance", "OMITTED",
                "crown", "OMITTED"));

        AssemblyCompileDiagnostics.clear();
        var prepared = ComponentSatelliteInference.prepareComponents(plan, Map.of(), false);
        assertTrue(prepared.components().isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_DESIGN_INCOMPLETE", AssemblyCompileDiagnostics.get().code());
        assertTrue(AssemblyCompileDiagnostics.get().message().contains("ROOF"));
    }

    @Test
    void multiMassScopedRoofsSurviveStrictPrepareAndCompile() {
        // Avoid "pavilion" in ids so PavilionOpenFrameRepair does not force open-frame
        // (window_style=none) and ExplicitDesignConflictRepair does not strip facades.
        Component house = mass("modern_house", Map.of(
                "component_id", "modern_house",
                "roof_type", "flat",
                "design_sources", Map.of("roof_type", "AI_PLANNED")));
        Component wing = mass("garden_wing", new Vec3i(20, 0, 0), new Dimensions(8, 8, 5), Map.of(
                "component_id", "garden_wing",
                "roof_type", "xieshan",
                "design_sources", Map.of("roof_type", "AI_PLANNED")));
        Component houseRoof = roof("roof_house", "modern_house", "flat", new Vec3i(0, 6, 0), new Dimensions(14, 12, 2));
        Component wingRoof = roof("roof_wing", "garden_wing", "xieshan",
                new Vec3i(20, 5, 0), new Dimensions(10, 10, 3));
        // Explicit facade/entrance satellites so strict mode does not demand inference.
        Component houseFacade = satellite("FACADE_WINDOWS", "facade_house", "modern_house",
                new Vec3i(0, 1, 0), new Dimensions(12, 1, 4));
        Component houseEntrance = satellite("ENTRANCE", "door_house", "modern_house",
                new Vec3i(5, 0, 10), new Dimensions(2, 1, 3));
        Component wingFacade = satellite("FACADE_WINDOWS", "facade_wing", "garden_wing",
                new Vec3i(20, 1, 0), new Dimensions(8, 1, 3));
        Component wingEntrance = satellite("ENTRANCE", "door_wing", "garden_wing",
                new Vec3i(23, 0, 8), new Dimensions(2, 1, 3));

        LlmPlan plan = strictPlan(
                List.of(house, wing, houseRoof, wingRoof,
                        houseFacade, houseEntrance, wingFacade, wingEntrance),
                Map.of(
                        "roof", "REQUIRED",
                        "facade", "REQUIRED",
                        "entrance", "REQUIRED",
                        "crown", "OMITTED"));

        AssemblyCompileDiagnostics.clear();
        var prepared = ComponentSatelliteInference.prepareComponents(plan, Map.of(), false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        // MASS roofs are suppressed to none when a hosted ROOF satellite exists; authorship lives on ROOF.
        assertEquals("flat", roofTypeOf(prepared.components(), "roof_house"),
                () -> dumpRoofParams(prepared.components()));
        assertEquals("xieshan", roofTypeOf(prepared.components(), "roof_wing"),
                () -> dumpRoofParams(prepared.components()));
        assertTrue(prepared.components().stream().anyMatch(c ->
                c != null && "ROOF".equalsIgnoreCase(c.componentType())
                        && "modern_house".equals(String.valueOf(c.params().get("host_id")))
                        && "flat".equalsIgnoreCase(String.valueOf(c.params().get("roof_type")))));
        assertTrue(prepared.components().stream().anyMatch(c ->
                c != null && "ROOF".equalsIgnoreCase(c.componentType())
                        && "garden_wing".equals(String.valueOf(c.params().get("host_id")))
                        && "xieshan".equalsIgnoreCase(String.valueOf(c.params().get("roof_type")))));

        AssemblyCompileDiagnostics.clear();
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());
        // Design property survival: both authored roof types remain on prepared hosts;
        // BlockPatch emission must not empty-out a complete multi-mass plan.
        assertTrue(patches.stream().anyMatch(p -> p.dy() >= 5),
                "expected roof-height patches for wing/house roofs");
    }

    private static String roofTypeOf(List<Component> components, String id) {
        return components.stream()
                .filter(c -> c != null && c.params() != null && id.equals(String.valueOf(c.params().get("component_id"))))
                .map(c -> String.valueOf(c.params().get("roof_type")))
                .findFirst()
                .orElse("<missing>");
    }

    private static String dumpRoofParams(List<Component> components) {
        StringBuilder sb = new StringBuilder();
        for (Component c : components) {
            if (c == null || c.params() == null) {
                continue;
            }
            String type = c.componentType();
            if (type == null) {
                continue;
            }
            String upper = type.toUpperCase();
            if (!upper.startsWith("ROOF") && !upper.startsWith("MASS")) {
                continue;
            }
            sb.append(type).append('{')
                    .append("id=").append(c.params().get("component_id"))
                    .append(", host=").append(c.params().get("host_id"))
                    .append(", roof_type=").append(c.params().get("roof_type"))
                    .append("}; ");
        }
        return sb.toString();
    }

    private static LlmPlan strictPlan(List<Component> components, Map<String, String> designRequirements) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("allow_compiler_satellites", false);
        meta.put("design_requirements", designRequirements);
        return LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .styleProfile("DEFAULT")
                .components(components)
                .planMeta(meta)
                .build();
    }

    private static Component mass(String slot, Map<String, Object> params) {
        return mass(slot, new Vec3i(0, 0, 0), new Dimensions(12, 10, 6), params);
    }

    private static Component mass(String slot, Vec3i pos, Dimensions dims, Map<String, Object> params) {
        return new Component("MASS_MAIN", slot, pos, dims, List.of(), params);
    }

    private static Component roof(String id, String hostId, String roofType, Vec3i pos, Dimensions dims) {
        return new Component(
                "ROOF",
                hostId,
                pos,
                dims,
                List.of(),
                Map.of(
                        "component_id", id,
                        "host_id", hostId,
                        "roof_type", roofType,
                        "design_sources", Map.of("roof_type", "AI_PLANNED")));
    }

    private static Component satellite(String type, String id, String hostId, Vec3i pos, Dimensions dims) {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", id);
        params.put("host_id", hostId);
        if ("ENTRANCE".equals(type)) {
            params.put("entrance_type", "door");
            params.put("door_width", 1);
            params.put("door_height", 3);
        }
        if ("FACADE_WINDOWS".equals(type)) {
            params.put("window_style", "regular");
            params.put("window_ratio", 0.2);
        }
        return new Component(type, hostId, pos, dims, List.of(), params);
    }
}
