package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostedPlateOccupancyTest {

    @Test
    void terraceAndBridgeSkipClip() {
        assertTrue(HostedPlateOccupancy.skipsHostFootprintClip(
                HostedPlateOccupancy.resolveRole(Map.of("plate_role", "terrace"))));
        assertTrue(HostedPlateOccupancy.skipsHostFootprintClip(
                HostedPlateOccupancy.resolveRole(Map.of("plate_role", "bridge"))));
        assertFalse(HostedPlateOccupancy.skipsHostFootprintClip(
                HostedPlateOccupancy.resolveRole(Map.of())));
        assertEquals(HostedPlateOccupancy.PlateRole.INTERIOR,
                HostedPlateOccupancy.resolveRole(Map.of("plate_role", "interior")));
    }

    @Test
    void courtyardHoleRejectsInteriorCell() {
        Component mass = new Component(
                "MASS_MAIN", "body", new Vec3i(0, 0, 0), new Dimensions(24, 24, 8),
                List.of("courtyard"),
                Map.of("plan_type", "courtyard", "courtyard_ratio", 0.5, "wall_thickness", 2,
                        "anchor_mode", "min_corner"));
        var geom = ResolvedBuildingGeometry.resolve(mass);
        var fp = geom.footprint();
        int cx = (fp.holeMinX() + fp.holeMaxX()) / 2;
        int cz = (fp.holeMinZ() + fp.holeMaxZ()) / 2;
        assertFalse(HostedPlateOccupancy.allowsHostedInteriorCell(
                geom, new Vec3i(0, 0, 0), cx, 4, cz));
        assertTrue(HostedPlateOccupancy.allowsHostedInteriorCell(
                geom, new Vec3i(0, 0, 0), 2, 4, 2));
    }
}
