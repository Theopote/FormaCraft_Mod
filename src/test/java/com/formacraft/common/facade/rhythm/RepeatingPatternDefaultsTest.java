package com.formacraft.common.facade.rhythm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RepeatingPatternDefaultsTest {

    @Test
    void classicalTypologyUsesPilasterBay() {
        RepeatingPattern pattern = RepeatingPatternDefaults.suggestFromHints(
                Map.of("typology", "classical_monument"), null);
        assertNotNull(pattern);
        assertEquals(5, pattern.unitWidth());
    }

    @Test
    void cottageTypologyUsesResidentialBay() {
        RepeatingPattern pattern = RepeatingPatternDefaults.suggestFromHints(
                Map.of("typology", "cottage"), null);
        assertNotNull(pattern);
        assertEquals(4, pattern.unitWidth());
    }

    @Test
    void unknownTypologyReturnsNull() {
        assertNull(RepeatingPatternDefaults.suggestFromHints(Map.of("typology", "cyber_tower"), null));
    }
}
