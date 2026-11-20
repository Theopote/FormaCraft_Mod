package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-repair fidelity: StadiumMisrouteRepair must not be undone by BaroqueMansionStyleRepair.
 */
class StadiumThenBaroqueFidelityChainTest {

    @BeforeAll
    static void init() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @BeforeEach
    void clearBefore() {
        AssemblyCompileDiagnostics.clear();
    }

    @AfterEach
    void clearAfter() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void stadiumThenBaroqueKeepsLockedHipAndDoesNotInventDormers() {
        Map<String, Object> mansionParams = new HashMap<>();
        mansionParams.put("component_id", "main");
        mansionParams.put("typology_id", "stadium_bowl");
        mansionParams.put("floor_count", 2);
        mansionParams.put("floor_height", 5);
        Component mansion = new Component(
                "STRUCTURE", "main",
                new Vec3i(0, 0, 0),
                new Dimensions(15, 25, 18),
                List.of("typology:stadium_bowl"),
                mansionParams);
        Map<String, Object> hipParams = new HashMap<>();
        hipParams.put("component_id", "main_roof");
        hipParams.put("host_id", "main");
        hipParams.put("roof_type", "hip");
        hipParams.put("design_sources", Map.of("roof_type", "USER_EXPLICIT"));
        Component hipRoof = new Component(
                "ROOF", "main",
                new Vec3i(0, 18, 0),
                new Dimensions(15, 25, 5),
                List.of(),
                hipParams);
        Component wing = new Component(
                "MASS_SECONDARY", "wing",
                new Vec3i(30, 0, 0),
                new Dimensions(10, 8, 6),
                List.of(),
                Map.of("component_id", "west_wing", "void_ratio", 0.15));
        Component wingRoof = new Component(
                "ROOF", "wing",
                new Vec3i(30, 6, 0),
                new Dimensions(10, 8, 3),
                List.of(),
                Map.of("component_id", "west_roof", "host_id", "west_wing", "roof_type", "flat"));
        Component chimneyW = new Component(
                "CHIMNEY", "main",
                new Vec3i(2, 20, 4),
                new Dimensions(2, 2, 4),
                List.of(),
                Map.of("component_id", "chimney_w", "host_id", "main"));
        Component chimneyE = new Component(
                "CHIMNEY", "main",
                new Vec3i(12, 20, 20),
                new Dimensions(2, 2, 4),
                List.of(),
                Map.of("component_id", "chimney_e", "host_id", "main"));
        Component arena = new Component(
                "STRUCTURE", "arena",
                new Vec3i(60, 0, 0),
                new Dimensions(40, 60, 20),
                List.of("typology:stadium_bowl"),
                Map.of("component_id", "east_arena", "typology_id", "stadium_bowl"));

        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("Modern_Stadium_Elliptical")
                .components(List.of(mansion, hipRoof, wing, wingRoof, chimneyW, chimneyE, arena))
                .planMeta(Map.of("request_intent_text",
                        "巴洛克大宅主楼用四坡屋顶不要老虎窗，两侧配楼平屋顶，庭院对称双烟囱，东侧另建一座体育场"))
                .build();

        LlmPlan afterStadium = StadiumMisrouteRepair.apply(plan);
        LlmPlan afterBaroque = BaroqueMansionStyleRepair.apply(afterStadium);

        Component main = afterBaroque.components().stream()
                .filter(c -> "main".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals("MASS_MAIN", main.componentType());
        assertFalse(String.valueOf(main.params().get("typology_id")).contains("stadium"));

        Component mainRoof = afterBaroque.components().stream()
                .filter(c -> "main_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals("hip", mainRoof.params().get("roof_type"));
        assertNull(mainRoof.params().get("roof_specialty"));
        assertNotEquals(true, mainRoof.params().get("roof_dormers"));

        Component westRoof = afterBaroque.components().stream()
                .filter(c -> "west_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals("flat", westRoof.params().get("roof_type"));
        assertNotEquals(true, westRoof.params().get("roof_dormers"));

        assertEquals(2, afterBaroque.components().stream()
                .filter(c -> "CHIMNEY".equalsIgnoreCase(c.componentType())).count());

        Component eastArena = afterBaroque.components().stream()
                .filter(c -> "east_arena".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals("STRUCTURE", eastArena.componentType());
        assertEquals("stadium_bowl", eastArena.params().get("typology_id"));

        Object specialty = afterBaroque.proportionHints() == null
                ? null : afterBaroque.proportionHints().get("roof_specialty");
        assertTrue(specialty == null
                || !String.valueOf(specialty).toLowerCase().contains("mansard"));
    }

    @Test
    void fullCompileKeepsLockedHipAfterStadiumAndBaroqueChain() {
        Map<String, Object> mansionParams = new HashMap<>();
        mansionParams.put("component_id", "main");
        mansionParams.put("typology_id", "stadium_bowl");
        mansionParams.put("window_style", "none");
        mansionParams.put("entrance_type", "none");
        Component mansion = new Component(
                "STRUCTURE", "main",
                new Vec3i(0, 0, 0),
                new Dimensions(12, 16, 10),
                List.of("typology:stadium_bowl"),
                mansionParams);
        Map<String, Object> hipParams = new HashMap<>();
        hipParams.put("component_id", "main_roof");
        hipParams.put("host_id", "main");
        hipParams.put("roof_type", "hip");
        hipParams.put("design_sources", Map.of("roof_type", "USER_EXPLICIT"));
        Component hipRoof = new Component(
                "ROOF", "main",
                new Vec3i(0, 10, 0),
                new Dimensions(12, 16, 4),
                List.of(),
                hipParams);
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("Modern_Stadium_Elliptical")
                .components(List.of(mansion, hipRoof))
                .planMeta(Map.of(
                        "allow_compiler_satellites", true,
                        "request_intent_text", "巴洛克大宅，屋顶使用四坡屋顶，不要老虎窗",
                        "design_requirements", Map.of(
                                "facade", "OMITTED",
                                "entrance", "OMITTED",
                                "crown", "OMITTED")))
                .build();

        var blocks = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(blocks.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
    }
}
