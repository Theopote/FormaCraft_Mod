package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoofHostedChimneyAlignmentTest {
    @Test void roofOwnershipClampsFootprintAndExtendsStackDownward() { verifyAttachment(false); }
    @Test void chimneyBeforeRoofUsesFinalRoofFrame() { verifyAttachment(true); }

    private void verifyAttachment(boolean chimneyFirst) {
        var mass = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(10, 14, 12), List.of(), Map.of("component_id", "mass"));
        var roof = new Component("ROOF", "main", new Vec3i(-6, 12, -8),
                new Dimensions(12, 16, 8), List.of(), Map.of("component_id", "roof", "anchor_mode", "min_corner"));
        var chimney = new Component("CHIMNEY", "main", new Vec3i(8, 15, 2),
                new Dimensions(2, 2, 5), List.of("brick_stack"), Map.of("host_id", "roof", "component_id", "chimney"));
        var components = new ArrayList<>(chimneyFirst ? List.of(mass, chimney, roof) : List.of(mass, roof, chimney));
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true).mode(LlmPlan.Mode.build).styleProfile("DEFAULT").components(components).build();
        ComponentSatelliteAligner.realignSatellitesToMass(components, plan, Map.of(), Set.of());
        var result = components.stream().filter(c -> "CHIMNEY".equals(c.componentType())).findFirst().orElseThrow();
        var alignedRoof = components.stream().filter(c -> "ROOF".equals(c.componentType())).findFirst().orElseThrow();
        assertEquals(alignedRoof.relativePosition().y(), result.relativePosition().y());
        assertTrue(result.relativePosition().x() >= alignedRoof.relativePosition().x());
        assertTrue(result.relativePosition().x() + result.dimensions().width()
                <= alignedRoof.relativePosition().x() + alignedRoof.dimensions().width());
        assertEquals(2, result.relativePosition().z());
        assertEquals(chimneyFirst ? "CHIMNEY" : "ROOF", components.get(1).componentType());
        assertEquals(20, result.relativePosition().y() + result.dimensions().height());
        assertEquals("roof", result.params().get("host_id"));
        assertEquals(true, result.params().get("resolved_host_attachment"));
    }
}
