package com.formacraft.common.generation.component.impl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MassMainRegionSuppressTest {
    @Test
    void regionWithMinFloorOnlySuppressesThatBand() {
        var regions = List.of(Map.<String, Object>of("face", "NORTH", "min_floor", 1, "max_floor", 1));
        // y=4, floorHeight=3 → floorIndex=1 on north wall (z=max)
        assertTrue(MassMainGenerator.isOpeningSuppressed(3, 7, 4, 8, 8, 3, Set.of(), regions));
        // floor 0
        assertFalse(MassMainGenerator.isOpeningSuppressed(3, 7, 1, 8, 8, 3, Set.of(), regions));
        // south wall not in region
        assertFalse(MassMainGenerator.isOpeningSuppressed(3, 0, 4, 8, 8, 3, Set.of(), regions));
    }

    @Test
    void faceOnlySuppressesWholeHeightWhenNoRegions() {
        assertTrue(MassMainGenerator.isOpeningSuppressed(3, 7, 1, 8, 8, 3, Set.of("NORTH"), List.of()));
        assertTrue(MassMainGenerator.isOpeningSuppressed(3, 7, 10, 8, 8, 3, Set.of("NORTH"), List.of()));
        assertFalse(MassMainGenerator.isOpeningSuppressed(3, 0, 1, 8, 8, 3, Set.of("NORTH"), List.of()));
    }
}
