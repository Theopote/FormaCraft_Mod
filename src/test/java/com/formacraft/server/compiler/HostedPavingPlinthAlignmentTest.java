package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HostedPavingPlinthAlignmentTest {
    private Component mass() {
        return new Component("MASS_MAIN", "tower_core", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 23), List.of(), Map.of("component_id", "tower_shaft"));
    }

    private Component align(Component paving) {
        var components = new ArrayList<>(List.of(mass(), paving));
        var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).styleProfile("DEFAULT")
                .components(components).build();
        ComponentSatelliteAligner.realignSatellitesToMass(components, plan, Map.of(), Set.of());
        return components.get(1);
    }

    @Test void clockTowerPlinthCentersBeneathItsHost() {
        var paving = new Component("PAVING", "tower_core", new Vec3i(0, -1, 0),
                new Dimensions(12, 12, 1), List.of("stone_plinth", "stepped_apron"),
                Map.of("component_id", "tower_plinth", "host_id", "tower_shaft", "paving_type", "platform"));
        var result = align(paving);
        assertEquals(new Vec3i(-6, -1, -6), result.relativePosition());
        assertEquals(new Dimensions(12, 12, 1), result.dimensions());
        assertEquals("min_corner", result.params().get("anchor_mode"));
    }

    @Test void ordinaryHostedPavingKeepsItsAuthoredPosition() {
        var paving = new Component("PAVING", "tower_core", new Vec3i(10, 0, 12),
                new Dimensions(4, 6, 1), List.of("entry_plaza"), Map.of("host_id", "tower_shaft"));
        assertEquals(paving.relativePosition(), align(paving).relativePosition());
    }

    @Test void unboundPlinthDoesNotSilentlyAttachToFirstBuilding() {
        var paving = new Component("PAVING", "tower_core", new Vec3i(10, -1, 12),
                new Dimensions(12, 12, 1), List.of("stone_plinth"), Map.of());
        assertEquals(paving.relativePosition(), align(paving).relativePosition());
    }
}
