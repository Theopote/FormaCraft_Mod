package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Thin facade strips must not be hardened like narrow tower shafts.
 */
class NarrowTowerStairRepairTest {

    @Test
    void doesNotDisableThinFacadeWindowStrip() {
        Component facade = new Component(
                "FACADE_WINDOWS",
                "villa_1",
                new Vec3i(-5, 1, 0),
                new Dimensions(10, 1, 4),
                List.of(),
                Map.of("window_ratio", 0.4, "window_aspect", "vertical_strip", "rhythm", "regular")
        );
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .components(List.of(facade))
                .build();

        LlmPlan repaired = NarrowTowerStairRepair.apply(plan);
        Component out = repaired.components().getFirst();
        assertNull(out.params().get("window_style"));
        assertFalse(Boolean.TRUE.equals(out.params().get("suppress_windows")));
        assertFalse(Boolean.TRUE.equals(out.params().get("hollow")));
    }

    @Test
    void keepsWindowsWhenExplicitFacadeBoundToNarrowTower() {
        Component tower = new Component(
                "MASS_MAIN",
                "tower",
                new Vec3i(0, 0, 0),
                new Dimensions(7, 7, 16),
                List.of(),
                Map.of("component_id", "tower", "hollow", true, "wall_thickness", 1)
        );
        Component facade = new Component(
                "FACADE_WINDOWS",
                "tower",
                new Vec3i(0, 0, 0),
                new Dimensions(7, 7, 16),
                List.of(),
                Map.of("host_id", "tower", "window_ratio", 0.15, "faces", List.of("SOUTH"))
        );
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .components(List.of(tower, facade))
                .build();

        LlmPlan repaired = NarrowTowerStairRepair.apply(plan);
        Component mass = repaired.components().stream()
                .filter(c -> "MASS_MAIN".equals(c.componentType()))
                .findFirst()
                .orElseThrow();
        assertNull(mass.params().get("window_style"));
        assertFalse(Boolean.TRUE.equals(mass.params().get("suppress_windows")));
        assertTrue(Boolean.TRUE.equals(mass.params().get("hollow")));
        assertEquals(1, ((Number) mass.params().get("wall_thickness")).intValue());
    }

    @Test
    void stillSuppressesWindowsOnNarrowTowerWithoutAuthoredFacade() {
        Component tower = new Component(
                "MASS_MAIN",
                "tower",
                new Vec3i(0, 0, 0),
                new Dimensions(7, 7, 16),
                List.of(),
                Map.of("component_id", "lonely_tower", "hollow", true, "wall_thickness", 1)
        );
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .components(List.of(tower))
                .build();

        LlmPlan repaired = NarrowTowerStairRepair.apply(plan);
        Component mass = repaired.components().getFirst();
        assertEquals("none", String.valueOf(mass.params().get("window_style")));
        assertTrue(Boolean.TRUE.equals(mass.params().get("suppress_windows")));
    }

    @Test
    void solidifiesTwoByTwoPinnacleInsteadOfUnfitHollowShell() {
        Component pinnacle = new Component(
                "MASS_MAIN",
                "tower",
                new Vec3i(0, 20, 0),
                new Dimensions(2, 2, 8),
                List.of("spire"),
                Map.of("component_id", "pinnacle_sw", "void_ratio", 0.15)
        );
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .components(List.of(pinnacle))
                .build();

        LlmPlan repaired = NarrowTowerStairRepair.apply(plan);
        Component mass = repaired.components().getFirst();
        assertEquals(false, mass.params().get("hollow"));
        assertEquals(0, ((Number) mass.params().get("void_ratio")).intValue());
        assertNull(mass.params().get("wall_thickness"));
        assertTrue(Boolean.TRUE.equals(mass.params().get("suppress_windows")));
    }
}
