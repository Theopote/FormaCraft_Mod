package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComponentPlanCompilerStructureTypologyTest {

    @Test
    void preservesStructureComponentWithTypologyFeature() throws Exception {
        Component input = new Component(
                "STRUCTURE",
                null,
                new Vec3i(0, 0, 0),
                new Dimensions(10, 10, 47),
                List.of("typology:dense_eaves_pagoda"),
                Map.of("levels", 13, "baseWidth", 10)
        );
        Component normalized = normalize(input);
        assertNotNull(normalized);
        assertEquals("STRUCTURE", normalized.componentType());
        assertTrue(normalized.features().stream().anyMatch(f -> f.contains("dense_eaves_pagoda")));
    }

    @Test
    void typologyExclusivePlanStripsCompositionalExtras() throws Exception {
        List<Component> input = List.of(
                new Component(
                        "STRUCTURE",
                        null,
                        new Vec3i(0, 0, 0),
                        new Dimensions(9, 180, 44),
                        List.of("typology:suspension_bridge"),
                        Map.of("typology_id", "suspension_bridge")
                ),
                new Component(
                        "MASS_MAIN",
                        null,
                        new Vec3i(0, 20, 0),
                        new Dimensions(10, 10, 6),
                        List.of(),
                        Map.of()
                ),
                new Component(
                        "TOWER",
                        null,
                        new Vec3i(0, 0, -25),
                        new Dimensions(4, 4, 30),
                        List.of(),
                        Map.of()
                ),
                new Component(
                        "ROOF",
                        null,
                        new Vec3i(-1, 6, -1),
                        new Dimensions(12, 12, 3),
                        List.of(),
                        Map.of()
                )
        );

        @SuppressWarnings("unchecked")
        List<Component> filtered = (List<Component>) invokeFilter(input);

        assertEquals(1, filtered.size());
        assertEquals("STRUCTURE", filtered.get(0).componentType());
        assertTrue(filtered.get(0).features().stream().anyMatch(f -> f.contains("suspension_bridge")));
    }

    @Test
    void typologyExclusivePlanKeepsPeripheralPaving() throws Exception {
        List<Component> input = List.of(
                new Component(
                        "STRUCTURE",
                        null,
                        new Vec3i(0, 0, 0),
                        new Dimensions(32, 28, 20),
                        List.of("typology:courtyard_compound"),
                        Map.of("typology_id", "courtyard_compound")
                ),
                new Component(
                        "PAVING",
                        null,
                        new Vec3i(0, 0, 0),
                        new Dimensions(8, 8, 1),
                        List.of(),
                        Map.of()
                ),
                new Component(
                        "MASS_MAIN",
                        null,
                        new Vec3i(0, 0, 0),
                        new Dimensions(10, 10, 8),
                        List.of(),
                        Map.of()
                )
        );

        @SuppressWarnings("unchecked")
        List<Component> filtered = (List<Component>) invokeFilter(input);

        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().anyMatch(c -> "STRUCTURE".equals(c.componentType())));
        assertTrue(filtered.stream().anyMatch(c -> "PAVING".equals(c.componentType())));
    }

    private static Object invokeFilter(List<Component> components) throws Exception {
        Method m = ComponentPlanCompiler.class.getDeclaredMethod("applyTypologyExclusiveFilter", List.class);
        m.setAccessible(true);
        return m.invoke(null, components);
    }

    private static Component normalize(Component component) throws Exception {
        Method m = ComponentPlanCompiler.class.getDeclaredMethod("normalizeComponent", Component.class);
        m.setAccessible(true);
        return (Component) m.invoke(null, component);
    }
}
