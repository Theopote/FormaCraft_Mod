package com.formacraft.server.terrain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerrainPadHeightTest {
    @Test void fillStartsAtFirstAirWithoutLeavingAHoleAboveTheSurface() {
        assertEquals(64,TerrainFit.padFillStart(64,65,4));
        assertEquals(64,TerrainFit.padFillStart(64,64,4));
        assertEquals(69,TerrainFit.padFillStart(64,73,4)); // Retain the shallow-fill budget.
    }
}
