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

    private static Component normalize(Component component) throws Exception {
        Method m = ComponentPlanCompiler.class.getDeclaredMethod("normalizeComponent", Component.class);
        m.setAccessible(true);
        return (Component) m.invoke(null, component);
    }
}
