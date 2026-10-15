package com.formacraft.common.generation.component.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoofPitchGeometryTest {

    @Test
    void steepPitchExceedsTechnicalCapOfEight() {
        // span 30 → half 15 → tan(58°)≈1.6 → rise ≈ 24
        int rise = RoofPitchGeometry.resolveRise(30, 30, 0, Map.of("roof_pitch_degrees", 58));
        assertTrue(rise > RoofPitchGeometry.TECHNICAL_MAX_RISE, "rise=" + rise);
        assertEquals(24, rise);
    }

    @Test
    void explicitRoofHeightWinsWhenConsistentWithPitch() {
        // pitch 38° on span 30 → rise ≈ 12; within ±1 of roof_height=12
        int rise = RoofPitchGeometry.resolveRise(30, 30, 0, Map.of(
                "roof_pitch_degrees", 38.7,
                "roof_height", 12));
        assertEquals(12, rise);
    }

    @Test
    void explicitRoofHeightAloneWins() {
        int rise = RoofPitchGeometry.resolveRise(30, 30, 0, Map.of("roof_height", 12));
        assertEquals(12, rise);
    }

    @Test
    void technicalDefaultStillCapsAtEight() {
        int rise = RoofPitchGeometry.resolveRise(30, 30, 0, Map.of());
        assertEquals(8, rise);
    }

    @Test
    void invalidNegativeHeightRejected() {
        var err = RoofPitchGeometry.validateRoofGeometryParams(20, 20, Map.of(
                "roof_height", -3,
                "roof_pitch_degrees", 95));
        assertTrue(err.isPresent());
        assertTrue(err.get().contains("roof_height") || err.get().contains("roof_pitch"));
        assertThrows(IllegalArgumentException.class,
                () -> RoofPitchGeometry.resolveRise(20, 20, 0, Map.of("roof_height", -3)));
    }

    @Test
    void conflictingHeightAndPitchRejected() {
        var err = RoofPitchGeometry.validateRoofGeometryParams(20, 20, Map.of(
                "roof_height", 3,
                "roof_pitch_degrees", 60));
        assertTrue(err.isPresent());
        assertTrue(err.get().contains("conflicts"));
    }
}
