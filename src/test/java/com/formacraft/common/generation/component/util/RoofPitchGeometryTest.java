package com.formacraft.common.generation.component.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void explicitRoofHeightWinsOverPitch() {
        int rise = RoofPitchGeometry.resolveRise(30, 30, 0, Map.of(
                "roof_pitch_degrees", 58,
                "roof_height", 12));
        assertEquals(12, rise);
    }

    @Test
    void technicalDefaultStillCapsAtEight() {
        int rise = RoofPitchGeometry.resolveRise(30, 30, 0, Map.of());
        assertEquals(8, rise);
    }
}
