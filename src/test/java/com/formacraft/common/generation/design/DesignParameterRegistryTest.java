package com.formacraft.common.generation.design;

import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesignParameterRegistryTest {

    @Test
    void canonicalKeyNormalizesHighImpactAliases() {
        assertEquals("roof_type", DesignParameterRegistry.canonicalKey("roofType"));
        assertEquals("entrance_type", DesignParameterRegistry.canonicalKey("entranceType"));
        assertEquals("window_style", DesignParameterRegistry.canonicalKey("window_style"));
        assertEquals("wall_thickness", DesignParameterRegistry.canonicalKey("wallThickness"));
        assertEquals("floor_count", DesignParameterRegistry.canonicalKey("floorCount"));
        assertEquals("door_width", DesignParameterRegistry.canonicalKey("doorWidth"));
        assertEquals("door_height", DesignParameterRegistry.canonicalKey("door_height"));
        assertEquals("rhythm_preset", DesignParameterRegistry.canonicalKey("facade_rhythm_preset"));
        assertEquals("overhang", DesignParameterRegistry.canonicalKey("eave_overhang"));
        assertEquals("crown_template", DesignParameterRegistry.canonicalKey("crownTemplate"));
    }

    @Test
    void putIfAllowedNormalizesUnlockedAlias() {
        Map<String, Object> params = new HashMap<>();
        params.put("design_sources", Map.of("roofType", "TECHNICAL_DEFAULT"));
        LlmPlan plan = LlmPlanTestFixtures.builder().build();

        assertTrue(DesignProvenance.putIfAllowed(plan, params, "roof_type", "gable"));
        assertEquals("gable", params.get("roof_type"));
    }

    @Test
    void putIfAllowedDoesNotRewriteLockedAlias() {
        Map<String, Object> params = new HashMap<>();
        params.put("windowStyle", "casement");
        params.put("design_sources", Map.of("windowStyle", "USER_EXPLICIT"));
        LlmPlan plan = LlmPlanTestFixtures.builder().build();

        assertFalse(DesignProvenance.putIfAllowed(plan, params, "window_style", "sash"));
        assertEquals("casement", params.get("windowStyle"));
        assertFalse(params.containsKey("window_style"));
    }

    @Test
    void removeIfAllowedRemovesUnlockedAliasIndependentlyOfLockedSibling() {
        Map<String, Object> params = new HashMap<>();
        params.put("door_width", 2);
        params.put("doorWidth", 2);
        params.put("design_sources", Map.of("doorWidth", "AI_PLANNED"));
        LlmPlan plan = LlmPlanTestFixtures.builder().build();

        // Exact-key removal: unlocked door_width may clear while locked doorWidth stays.
        assertTrue(DesignProvenance.removeIfAllowed(plan, params, "door_width"));
        assertFalse(params.containsKey("door_width"));
        assertEquals(2, params.get("doorWidth"));
    }
}
