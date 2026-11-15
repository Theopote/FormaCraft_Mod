package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoofHostedChimneyAlignmentTest {
    @Test void roofOwnershipClampsFootprintAndExtendsStackDownward() {
        var mass = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(10, 14, 12), List.of(), Map.of("component_id", "mass"));
        var roof = new Component("ROOF", "main", new Vec3i(-6, 12, -8),
                new Dimensions(12, 16, 8), List.of(), Map.of("component_id", "roof", "anchor_mode", "min_corner"));
        var chimney = new Component("CHIMNEY", "main", new Vec3i(8, 15, 2),
                new Dimensions(2, 2, 5), List.of("brick_stack"), Map.of("host_id", "roof", "component_id", "chimney"));
        var components = new ArrayList<>(List.of(mass, roof, chimney));
        var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).styleProfile("DEFAULT").components(components).build();
        ComponentSatelliteAligner.realignSatellitesToMass(components, plan, Map.of(), Set.of());
        var result = components.get(2);
        var alignedRoof = components.get(1);
        assertEquals(alignedRoof.relativePosition().y(), result.relativePosition().y());
        assertTrue(result.relativePosition().x() >= alignedRoof.relativePosition().x());
        assertTrue(result.relativePosition().x() + result.dimensions().width()
                <= alignedRoof.relativePosition().x() + alignedRoof.dimensions().width());
        assertEquals(2, result.relativePosition().z());
        assertEquals(20, result.relativePosition().y() + result.dimensions().height());
        assertEquals("roof", result.params().get("host_id"));
        assertEquals(true, result.params().get("resolved_host_attachment"));
    }
}
