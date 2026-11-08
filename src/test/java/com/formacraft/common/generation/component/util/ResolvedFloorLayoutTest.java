package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResolvedFloorLayoutTest {

    @Test
    void usesExplicitFloorHeight() {
        Component c = mass(Map.of("floor_height", 2, "floor_count", 3));
        var layout = ResolvedFloorLayout.from(c, 6);
        assertEquals(2, layout.bandHeight());
        assertEquals(3, layout.floorCount());
        assertTrue(layout.fits());
    }

    @Test
    void derivesFromFloorCountHonorsMinClearHeight() {
        Component c = mass(Map.of("floor_count", 4));
        // 8/4 → 2, but min storey band (clear 3 → band 4) clamps upward.
        var layout = ResolvedFloorLayout.from(c, 8);
        assertEquals(InteriorSpaceDefaults.minStoreyBand(), layout.bandHeight());
    }

    @Test
    void technicalDefaultUsesTallStoreyWhenEnvelopeIsTall() {
        Component c = mass(Map.of());
        var layout = ResolvedFloorLayout.from(c, 10);
        assertEquals(InteriorSpaceDefaults.defaultStoreyHeight(10), layout.bandHeight());
    }

    @Test
    void tallerGroundClearAvoidsLeftoverHalfStorey() {
        Component c = mass(Map.of(
                "floor_count", 3,
                "floor_height", 3,
                "ground_clear_height", 4));
        var layout = ResolvedFloorLayout.from(c, 11);
        assertEquals(List.of(0, 4, 7), layout.floorYs());
        assertTrue(layout.fits());
    }

    private static Component mass(Map<String, Object> params) {
        return new Component(
                "MASS_MAIN",
                "body",
                new Vec3i(0, 0, 0),
                new Dimensions(10, 10, 10),
                List.of(),
                params);
    }
}
