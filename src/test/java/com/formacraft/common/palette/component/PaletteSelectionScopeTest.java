package com.formacraft.common.palette.component;

import com.formacraft.common.llm.dto.*;
import com.formacraft.common.semantic.SemanticPart;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PaletteSelectionScopeTest {
    private List<String> choices(long seed, String id) {
        var plan = LlmPlanTestFixtures.builder().styleProfile("MEDIEVAL").proportionHints(Map.of("design_seed", seed)).build();
        var component = new Component("MASS_MAIN", "s", new Vec3i(0, 0, 0), new Dimensions(12, 12, 8),
                List.of(), Map.of("component_id", id));
        try (var scope = PaletteSelectionScope.open(plan, component)) {
            var result = new ArrayList<String>();
            for (int i = 0; i < 100; i++) result.add(PaletteLibrary.forStyle("MEDIEVAL").pick(SemanticPart.WALL));
            return result;
        }
    }
    @Test void componentOrderCannotChangeMaterialStreams() {
        var a = choices(123, "a");
        choices(123, "b");
        assertEquals(a, choices(123, "a"));
        assertNotEquals(a, choices(124, "a"));
        assertNotEquals(a, choices(123, "b"));
        assertNull(PaletteSelectionScope.current());
    }
}
