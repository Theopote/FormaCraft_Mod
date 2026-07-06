package com.formacraft.common.facade.rhythm;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepeatingPatternParserTest {

    @Test
    void parsesSuggestedJsonShape() {
        Map<String, Object> pattern = new LinkedHashMap<>();
        pattern.put("unit_width_z", 5);
        pattern.put("elements", List.of(
                Map.of("type", "pillar", "width", 1),
                Map.of("type", "window", "width", 3, "inset", 0),
                Map.of("type", "pillar", "width", 1)
        ));

        RepeatingPattern parsed = RepeatingPatternParser.parseValue(pattern);
        assertNotNull(parsed);
        assertTrue(parsed.isValid());
        assertEquals(5, parsed.unitWidth());
        assertEquals(3, parsed.elements().size());
    }

    @Test
    void rejectsMismatchedUnitWidth() {
        Map<String, Object> pattern = new LinkedHashMap<>();
        pattern.put("unit_width_z", 7);
        pattern.put("elements", List.of(
                Map.of("type", "pillar", "width", 1),
                Map.of("type", "window", "width", 3),
                Map.of("type", "pillar", "width", 1)
        ));
        assertTrue(RepeatingPatternParser.parseValue(pattern) == null
                || !RepeatingPatternParser.parseValue(pattern).isValid());
    }
}
