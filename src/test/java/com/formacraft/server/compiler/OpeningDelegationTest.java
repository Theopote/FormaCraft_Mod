package com.formacraft.server.compiler;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OpeningDelegationTest {
    @Test
    void fullPlusPartialIsFull() {
        var merged = OpeningDelegation.full().merge(OpeningDelegation.faces(Set.of("NORTH")));
        assertTrue(merged.fullMass());
        assertTrue(merged.faceNames().isEmpty());
    }

    @Test
    void partialUnionPartial() {
        var merged = OpeningDelegation.faces(Set.of("NORTH"))
                .merge(OpeningDelegation.faces(Set.of("SOUTH")));
        assertFalse(merged.fullMass());
        assertEquals(Set.of("NORTH", "SOUTH"), merged.faceNames());
    }

    @Test
    void noneMergesToOther() {
        assertTrue(OpeningDelegation.none().merge(OpeningDelegation.full()).fullMass());
        assertEquals(Set.of("EAST"),
                OpeningDelegation.none().merge(OpeningDelegation.faces(Set.of("EAST"))).faceNames());
    }

    @Test
    void satelliteWithoutFacesIsFull() {
        assertTrue(OpeningDelegation.fromSatelliteParams(Map.of("component_id", "w1")).fullMass());
    }

    @Test
    void satelliteWithFacesIsPartialWithFloors() {
        var d = OpeningDelegation.fromSatelliteParams(Map.of(
                "faces", "NORTH",
                "min_floor", 1,
                "max_floor", 2
        ));
        assertFalse(d.fullMass());
        assertEquals(1, d.regions().size());
        var r = d.regions().iterator().next();
        assertEquals("NORTH", r.face());
        assertEquals(1, r.minFloor());
        assertEquals(2, r.maxFloor());
        assertTrue(r.coversFloor(1));
        assertFalse(r.coversFloor(0));
    }

    @Test
    void facingAloneDoesNotCountAsFaceList() {
        // entrance orientation must not become partial suppress
        assertTrue(OpeningDelegation.fromSatelliteParams(Map.of("facing", "SOUTH")).fullMass());
    }
}
