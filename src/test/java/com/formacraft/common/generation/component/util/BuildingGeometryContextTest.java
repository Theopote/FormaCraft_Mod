package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildingGeometryContextTest {

    @Test
    void nestedScopeRestoresCacheAfterException() {
        var mass = new Component("MASS_MAIN", "body", new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 8), List.of(), Map.of("component_id", "body"));
        try (var outer = BuildingGeometryContext.open()) {
            BuildingGeometryContext.putMass(mass);
            var original = BuildingGeometryContext.get("body");
            assertNotNull(original);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> {
                try (var inner = BuildingGeometryContext.open()) {
                    assertNull(BuildingGeometryContext.get("body"));
                    BuildingGeometryContext.putMass(mass);
                    org.junit.jupiter.api.Assertions.assertNotSame(original, BuildingGeometryContext.get("body"));
                    throw new IllegalStateException("abort");
                }
            });
            assertSame(original, BuildingGeometryContext.get("body"));
            assertTrue(BuildingGeometryContext.isActive());
        }
        assertNull(BuildingGeometryContext.get("body"));
        org.junit.jupiter.api.Assertions.assertFalse(BuildingGeometryContext.isActive());
    }

    @AfterEach
    void tearDown() {
        BuildingGeometryContext.end();
    }

    @Test
    void putMassCachesGeometryUntilEnd() {
        BuildingGeometryContext.begin();
        Component mass = new Component(
                "MASS_MAIN", "body", new Vec3i(0, 0, 0), new Dimensions(12, 10, 8),
                List.of(),
                Map.of("component_id", "body", "plan_type", "courtyard", "courtyard_ratio", 0.4,
                        "wall_thickness", 2, "anchor_mode", "min_corner"));
        BuildingGeometryContext.putMass(mass);
        var entry = BuildingGeometryContext.get("body");
        assertNotNull(entry);
        assertSame(entry, BuildingGeometryContext.get("body"));
        assertTrue(entry.geometry().layers() instanceof java.util.List);
        BuildingGeometryContext.end();
        assertNull(BuildingGeometryContext.get("body"));
    }

    @Test
    void plateExtrudeModeIsNotCachedAsHost() {
        BuildingGeometryContext.begin();
        Component plate = new Component(
                "MASS_SECONDARY", "body", new Vec3i(0, 4, 0), new Dimensions(12, 10, 1),
                List.of(),
                Map.of("component_id", "floor", "host_id", "body", "extrude_mode", "plate",
                        "anchor_mode", "min_corner"));
        BuildingGeometryContext.putMass(plate);
        assertNull(BuildingGeometryContext.get("floor"));
    }
}
