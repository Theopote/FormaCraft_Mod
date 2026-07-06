package com.formacraft.common.network.metrics;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypologyRoutingMetricsTest {

    @Test
    void detectsCompositionalMassComponent() {
        Component c = new Component(
                "MASS_MAIN",
                null,
                new Vec3i(0, 0, 0),
                new Dimensions(10, 10, 10),
                List.of("elliptical_footprint"),
                Map.of()
        );
        assertTrue(TypologyRoutingMetrics.isCompositionalComponent(c));
        assertFalse(TypologyRoutingMetrics.isModuleComponent(c));
    }

    @Test
    void detectsModuleLandmarkAsStructureGeneratorHint() {
        Component c = new Component(
                "MODULE",
                null,
                new Vec3i(0, 0, 0),
                new Dimensions(10, 10, 10),
                List.of("landmark:birds_nest_stadium"),
                Map.of()
        );
        assertTrue(TypologyRoutingMetrics.isModuleComponent(c));
        assertTrue(TypologyRoutingMetrics.hasStructureGeneratorHint(c));
    }

    @Test
    void typologyStructureIsNotStructureGeneratorHint() {
        Component c = new Component(
                "STRUCTURE",
                null,
                new Vec3i(0, 0, 0),
                new Dimensions(36, 36, 34),
                List.of("typology:radial_terrace_hall"),
                Map.of("typology_id", "radial_terrace_hall")
        );
        assertFalse(TypologyRoutingMetrics.hasStructureGeneratorHint(c));
    }
}
