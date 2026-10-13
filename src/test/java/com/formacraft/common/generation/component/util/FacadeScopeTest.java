package com.formacraft.common.generation.component.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FacadeScopeTest {
    @Test
    void emptyFacesMeansFullMass() {
        var scope = FacadeScope.parse(Map.of("component_id", "w1"));
        assertTrue(scope.isFullMass());
        assertTrue(scope.covers("NORTH", 0));
        assertTrue(scope.covers("SOUTH", 99));
    }

    @Test
    void facesAndFloorBoundsRestrictCoverage() {
        var scope = FacadeScope.parse(Map.of(
                "faces", List.of("SOUTH"),
                "min_floor", 0,
                "max_floor", 0));
        assertFalse(scope.isFullMass());
        assertTrue(scope.covers("SOUTH", 0));
        assertFalse(scope.covers("SOUTH", 1));
        assertFalse(scope.covers("NORTH", 0));
    }

    @Test
    void openingDelegationSharesParseRules() {
        var d = com.formacraft.server.compiler.OpeningDelegation.fromSatelliteParams(Map.of(
                "faces", "EAST",
                "min_floor", 1,
                "max_floor", 2));
        assertFalse(d.fullMass());
        assertEquals(java.util.Set.of("EAST"), d.faceNames());
        var region = d.regions().iterator().next();
        assertEquals(1, region.minFloor());
        assertEquals(2, region.maxFloor());
    }
}
