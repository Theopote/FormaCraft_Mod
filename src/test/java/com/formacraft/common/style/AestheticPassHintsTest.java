package com.formacraft.common.style;

import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AestheticPassHintsTest {

    @Test
    void lightingStyleFalseDisablesToNoneWithoutNpe() {
        Map<String, Object> params = new HashMap<>();
        params.put("lighting_style", false);
        var resolved = AestheticPassHints.resolve(plan("medieval"), params);
        assertEquals(AestheticPassHints.LightingStyle.NONE, resolved.lightingStyle());
        params.put("lighting_density", "off");
        resolved = AestheticPassHints.resolve(plan("medieval"), params);
        assertEquals(AestheticPassHints.LightingDensity.LOW, resolved.lightingDensity());
    }

    @Test
    void planMassAndComponentDisableScopes() {
        var mass = AestheticPassHints.resolve(plan("gothic"), Map.of(
                "facade_relief", "none",
                "window_trim", "off",
                "weathering", 0,
                "lighting_style", "false"));
        assertEquals(AestheticPassHints.FacadeRelief.FLAT, mass.facadeRelief());
        assertEquals(AestheticPassHints.WindowTrim.NONE, mass.windowTrim());
        assertEquals(AestheticPassHints.Weathering.NONE, mass.weathering());
        assertEquals(AestheticPassHints.LightingStyle.NONE, mass.lightingStyle());
    }

    @Test
    void invalidTokenDoesNotSilentStyleDefault() {
        var resolved = AestheticPassHints.resolve(plan("medieval"), Map.of(
                "lighting_style", "not_a_catalog_style"));
        assertNull(resolved.lightingStyle(), "INVALID must not become WARM_LANTERN");
    }

    @Test
    void absentStillReceivesStyleDefault() {
        var resolved = AestheticPassHints.resolve(plan("medieval"), Map.of());
        assertEquals(AestheticPassHints.LightingStyle.WARM_LANTERN, resolved.lightingStyle());
        assertEquals(AestheticPassHints.FacadeRelief.DEEP, resolved.facadeRelief());
    }

    private static LlmPlan plan(String style) {
        return LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).styleProfile(style).build();
    }
}
