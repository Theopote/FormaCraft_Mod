package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoofHostedDormerAlignmentTest {
    @Test void boundsThreeDormersAgainstFinalRoofWithoutCenteringThem() {
        for (boolean dormersFirst : List.of(false, true)) {
            var mass = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0),
                    new Dimensions(10, 14, 12), List.of(), Map.of("component_id", "mass"));
            var roof = new Component("ROOF", "main", new Vec3i(1, 13, 1),
                    new Dimensions(12, 16, 8), List.of(), Map.of("component_id", "roof", "host_id", "mass"));
            var dormers = List.of(dormer("a", -20, -20, "roof", "main"),
                    dormer("b", -20, 0, "roof", "main"), dormer("c", -20, 20, "roof", "main"));
            var components = new ArrayList<Component>();
            components.add(mass);
            if (dormersFirst) components.addAll(dormers);
            components.add(roof);
            if (!dormersFirst) components.addAll(dormers);
            var originalIds = components.stream().map(c -> c.params().get("component_id")).toList();
            var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).styleProfile("DEFAULT").components(components).build();
            ComponentSatelliteAligner.realignSatellitesToMass(components, plan, Map.of(), Set.of());
            var finalRoof = components.stream().filter(c -> "ROOF".equals(c.componentType())).findFirst().orElseThrow();
            var origin = ComponentPlanCompiler.resolveMassOrigin(finalRoof);
            var positions = new HashSet<Vec3i>();
            for (var result : components) {
                if (!"roof".equals(result.params().get("host_id"))) continue;
                var p = result.relativePosition();
                assertTrue(p.x() >= origin.x());
                assertTrue(p.z() >= origin.z());
                assertTrue(p.x() + 3 <= origin.x() + finalRoof.dimensions().width());
                assertTrue(p.z() + 3 <= origin.z() + finalRoof.dimensions().depth());
                assertEquals(14, p.y());
                assertEquals(new Dimensions(3, 3, 3), result.dimensions());
                positions.add(p);
            }
            assertEquals(3, positions.size());
            assertEquals(originalIds, components.stream().map(c -> c.params().get("component_id")).toList());
        }
    }

    @Test void unresolvedOrDifferentSlotHostsDoNotMoveDormers() {
        var roof = new Component("ROOF", "main", new Vec3i(0, 12, 0),
                new Dimensions(12, 16, 8), List.of(), Map.of("component_id", "roof"));
        var missing = dormer("missing", 20, 20, "unknown", "main");
        var otherSlot = dormer("other", 20, 20, "roof", "other");
        var components = new ArrayList<>(List.of(roof, missing, otherSlot));
        var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).styleProfile("DEFAULT").components(components).build();
        ComponentSatelliteAligner.realignSatellitesToMass(components, plan, Map.of(), Set.of());
        assertSame(missing, components.get(1));
        assertSame(otherSlot, components.get(2));
    }

    private Component dormer(String id, int x, int z, String host, String slot) {
        return new Component("MASS_ANCILLARY", slot, new Vec3i(x, 14, z), new Dimensions(3, 3, 3),
                List.of("dormer"), Map.of("component_id", id, "host_id", host, "anchor_mode", "min_corner"));
    }
}
