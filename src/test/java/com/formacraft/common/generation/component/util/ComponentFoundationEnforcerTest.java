package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentFoundationEnforcerTest {

    @Test
    void expandsFoundationToCoverMassDepthWithMargin() {
        Component foundation = new Component(
                "FOUNDATION",
                null,
                new Vec3i(-2, -1, -2),
                new Dimensions(20, 20, 1),
                List.of(),
                Map.of("anchor_mode", "min_corner")
        );
        Component mass = new Component(
                "MASS_MAIN",
                null,
                new Vec3i(0, 0, 0),
                new Dimensions(16, 20, 9),
                List.of(),
                Map.of("anchor_mode", "min_corner")
        );

        List<Component> components = new ArrayList<>(List.of(foundation, mass));
        assertEquals(1, ComponentFoundationEnforcer.apply(components, 2));

        Component expanded = components.get(0);
        ComponentFootprintUtil.Bounds found = ComponentFootprintUtil.bounds(expanded);
        ComponentFootprintUtil.Bounds massBounds = ComponentFootprintUtil.bounds(mass);

        assertTrue(found.contains(massBounds.expandHorizontal(2)));
        assertEquals(-2, found.minX());
        assertEquals(-2, found.minZ());
        assertEquals(18, found.maxX());
        assertEquals(22, found.maxZ());
        assertEquals(20, found.width());
        assertEquals(24, found.depth());
    }

    @Test
    void expandsCenterAnchoredFoundationForDeepMass() {
        Component foundation = new Component(
                "FOUNDATION",
                null,
                new Vec3i(0, 0, 0),
                new Dimensions(23, 20, 2),
                List.of(),
                Map.of("anchor_mode", "center")
        );
        Component mass = new Component(
                "MASS_MAIN",
                null,
                new Vec3i(0, 2, 0),
                new Dimensions(21, 26, 13),
                List.of(),
                Map.of("anchor_mode", "center")
        );

        List<Component> components = new ArrayList<>(List.of(foundation, mass));
        ComponentFoundationEnforcer.apply(components, 2);

        ComponentFootprintUtil.Bounds found = ComponentFootprintUtil.bounds(components.get(0));
        ComponentFootprintUtil.Bounds massBounds = ComponentFootprintUtil.bounds(mass);

        assertTrue(found.contains(massBounds.expandHorizontal(2)));
        assertTrue(found.maxZ() >= massBounds.maxZ() + 2);
    }
}
