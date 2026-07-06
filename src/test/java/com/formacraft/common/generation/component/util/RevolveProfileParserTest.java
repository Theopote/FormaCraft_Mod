package com.formacraft.common.generation.component.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RevolveProfileParserTest {

    @Test
    void resolvesNormalizedProfileCurveYRadius() {
        Map<String, Object> params = Map.of(
                "generation_method", "revolved_surface_around_axis",
                "profile_curve_y_radius", List.of(
                        Map.of("y_rel", 0.0, "radius", 0.5),
                        Map.of("y_rel", 0.5, "radius", 0.6),
                        Map.of("y_rel", 1.0, "radius", 0.0)
                )
        );
        List<double[]> profile = RevolveProfileParser.resolve(params);
        assertNotNull(profile);
        assertEquals(3, profile.size());
        assertEquals(0.5, profile.getFirst()[0], 1e-6);
        assertEquals(0.0, profile.getFirst()[1], 1e-6);
        assertEquals(0.0, profile.getLast()[0], 1e-6);
    }

    @Test
    void normalizesAbsoluteBlockSteps() {
        List<double[]> profile = RevolveProfileParser.normalizeProfile(List.of(
                new double[] {4, 0},
                new double[] {4, 1},
                new double[] {3, 2},
                new double[] {1, 5},
                new double[] {0, 6}
        ));
        assertNotNull(profile);
        assertEquals(1.0, profile.get(1)[1], 1e-6);
        assertEquals(0.0, profile.getLast()[0], 1e-6);
        assertEquals(1.0, profile.getLast()[1], 1e-6);
    }

    @Test
    void parsesAssemblyStyleProfilePoints() {
        Map<String, Object> params = Map.of(
                "profilePoints", List.of(
                        Map.of("x", 2, "y", 0),
                        Map.of("x", 4, "y", 3),
                        Map.of("x", 0, "y", 6)
                )
        );
        List<double[]> profile = RevolveProfileParser.resolve(params);
        assertNotNull(profile);
        assertEquals(0.0, profile.getLast()[0], 1e-6);
    }

    @Test
    void toAssemblyProfilePoints_scalesToBlockCoords() {
        List<double[]> normalized = CrownTemplateLibrary.profile(CrownTemplateLibrary.SIMPLE_DOME);
        List<Map<String, Object>> points = RevolveProfileParser.toAssemblyProfilePoints(normalized, 4.0, 8);
        assertTrue(points.size() >= 2);
        assertEquals(0, ((Number) points.getFirst().get("y")).intValue());
        assertTrue(((Number) points.getLast().get("y")).intValue() >= 7);
    }

    @Test
    void returnsNullWhenMissingProfile() {
        assertNull(RevolveProfileParser.resolve(new LinkedHashMap<>()));
    }
}
