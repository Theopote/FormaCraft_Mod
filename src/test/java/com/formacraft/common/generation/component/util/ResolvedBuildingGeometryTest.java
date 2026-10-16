package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ResolvedBuildingGeometryTest {

    @Test
    void aggregatesFootprintFloorsAndLayers() {
        Component mass = new Component(
                "MASS_MAIN",
                "body",
                new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 16),
                List.of(),
                Map.of(
                        "plan_type", "courtyard",
                        "courtyard_ratio", 0.4,
                        "wall_thickness", 2,
                        "floor_count", 4,
                        "floor_height", 4,
                        "anchor_mode", "min_corner"));
        var geom = ResolvedBuildingGeometry.resolve(mass);
        assertNotNull(geom);
        assertSame(mass, geom.host());
        assertEquals(ResolvedFootprint.PlanPattern.COURTYARD, geom.footprint().pattern());
        assertEquals(List.of(0, 4, 8, 12), geom.floors().floorYs());
        assertEquals(4, geom.floors().bandHeight());
        assertEquals(16, geom.layers().size());
        assertEquals(geom.footprint().width(), 12);
        assertEquals(geom.footprint().depth(), 10);
    }

    @Test
    void worldCoordFloorYsMatchFacadeBandAuthority() {
        Component mass = new Component(
                "MASS_MAIN",
                "body",
                new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 16),
                List.of(),
                Map.of("floor_count", 4, "floor_height", 4));
        var geom = ResolvedBuildingGeometry.resolve(mass);
        assertNotNull(geom);
        // World-local storey bases used by Mass + Facade must agree with ResolvedFloorLayout alone.
        var floorsOnly = ResolvedFloorLayout.from(mass, 16);
        assertEquals(floorsOnly.floorYs(), geom.floors().floorYs());
        assertEquals(floorsOnly.bandHeight(), geom.floors().bandHeight());
    }
}
