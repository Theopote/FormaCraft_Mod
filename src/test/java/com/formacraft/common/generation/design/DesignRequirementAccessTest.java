package com.formacraft.common.generation.design;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DesignRequirementAccessTest {

    @Test
    void planMetaOmittedOverridesUnspecifiedDefault() {
        Component mass = new Component("MASS_MAIN", "b", new Vec3i(0, 0, 0),
                new Dimensions(6, 6, 4), List.of(), Map.of());
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(mass))
                .planMeta(Map.of("design_requirements", Map.of("facade", "OMITTED")))
                .build();
        assertEquals(DesignRequirement.OMITTED, DesignRequirementAccess.facade(plan, mass));
    }

    @Test
    void explicitDisableMapsToOmittedWhenNoMeta() {
        Component mass = new Component("MASS_MAIN", "b", new Vec3i(0, 0, 0),
                new Dimensions(6, 6, 4), List.of(), Map.of("roof_type", "none"));
        LlmPlan plan = LlmPlanTestFixtures.builder().components(List.of(mass)).build();
        assertEquals(DesignRequirement.OMITTED, DesignRequirementAccess.roof(plan, mass));
    }
}
