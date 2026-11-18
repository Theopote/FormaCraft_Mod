package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.typology.TypologyRoofCapability;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TypologyRoofCapabilityValidatorTest {

    @Test
    void denseEavesPagodaOmitRoofIsUnsupported() {
        assertEquals(TypologyRoofCapability.Support.UNSUPPORTED,
                TypologyRoofCapability.evaluate("dense_eaves_pagoda", "none"));
        Optional<CapabilityGap> gap = DesignValidatorSuite.checkAuthored(plan(
                pagoda(Map.of("component_id", "pagoda_shell", "typology_id", "dense_eaves_pagoda",
                        "roof_type", "none"))));
        assertTrue(gap.isPresent());
        assertEquals("E_TYPOLOGY_ROOF_UNSUPPORTED", gap.get().code());
    }

    @Test
    void denseEavesPagodaPrefillXieshanStillUnsupported() {
        // Param match must not pretend the generator executes snake_case roof_type.
        Optional<CapabilityGap> gap = DesignValidatorSuite.checkAuthored(plan(
                pagoda(Map.of("component_id", "pagoda_shell", "typology_id", "dense_eaves_pagoda",
                        "roof_type", "xieshan"))));
        assertTrue(gap.isPresent());
        assertEquals("E_TYPOLOGY_ROOF_UNSUPPORTED", gap.get().code());
    }

    @Test
    void denseEavesWithoutExplicitRoofTypePassesCapabilityGate() {
        Optional<CapabilityGap> gap = DesignValidatorSuite.checkAuthored(plan(
                pagoda(Map.of("component_id", "pagoda_shell", "typology_id", "dense_eaves_pagoda"))));
        assertTrue(gap.isEmpty() || !"E_TYPOLOGY_ROOF_UNSUPPORTED".equals(gap.get().code()),
                () -> String.valueOf(gap.orElse(null)));
    }

    @Test
    void tailiangOmitRoofIsSupportedCapability() {
        assertEquals(TypologyRoofCapability.Support.SUPPORTED,
                TypologyRoofCapability.evaluate("tailiang_timber_hall", "none"));
    }

    private static LlmPlan plan(Component... components) {
        return com.formacraft.common.llm.dto.LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .mode(LlmPlan.Mode.build)
                .components(List.of(components))
                .build();
    }

    private static Component pagoda(Map<String, Object> params) {
        return new Component(
                "STRUCTURE", "pagoda",
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new Dimensions(9, 9, 28),
                List.of("typology:dense_eaves_pagoda"),
                params);
    }
}
