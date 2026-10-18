package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.server.generation.component.impl.AssemblyPatchGenerator;
import com.formacraft.server.generation.component.impl.StraightStairComponentGenerator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComponentCompilationPriorityTest {
    @Test
    void disorderedInputSortsIntoSemanticStages() throws Exception {
        Method priority = ComponentPlanCompiler.class.getDeclaredMethod("compilationPriority", Component.class);
        priority.setAccessible(true);

        Component mass = component("MASS_MAIN", "m");
        Component floor = component("FLOOR", "fl");
        Component roof = component("ROOF", "r");
        Component facade = component("FACADE", "f");
        Component entrance = component("ENTRANCE", "e");
        Component windows = component("FACADE_WINDOWS", "w");
        Component decor = component("DECOR_DETAIL", "d");
        Component stair = new Component("STRUCTURE", "s", new Vec3i(0, 0, 0), null,
                List.of("stair:straight_single_run"), Map.of("from", Map.of("x", 0, "y", 0, "z", 0),
                        "to", Map.of("x", 3, "y", 3, "z", 0)));

        assertEquals(0, (int) priority.invoke(null, mass));
        assertEquals(1, (int) priority.invoke(null, floor));
        assertEquals(2, (int) priority.invoke(null, roof));
        assertEquals(3, (int) priority.invoke(null, facade));
        assertEquals(4, (int) priority.invoke(null, entrance));
        assertEquals(5, (int) priority.invoke(null, windows));
        assertEquals(6, (int) priority.invoke(null, decor));
        assertEquals(7, (int) priority.invoke(null, stair), "explicit stairs must compile after floors");

        assertFalse(StraightStairComponentGenerator.accepts(mass));
        assertFalse(StraightStairComponentGenerator.accepts(decor));
        assertFalse(AssemblyPatchGenerator.hasCirculation(mass));
        assertFalse(AssemblyPatchGenerator.hasCirculation(decor));
        assertTrue(StraightStairComponentGenerator.accepts(stair));

        List<Component> disordered = new ArrayList<>(List.of(
                decor, entrance, mass, facade, roof, floor, windows));
        disordered.sort(Comparator.comparingInt(c -> {
            try {
                return (int) priority.invoke(null, c);
            } catch (ReflectiveOperationException ex) {
                throw new RuntimeException(ex);
            }
        }));

        assertEquals("MASS_MAIN", disordered.get(0).componentType());
        assertEquals("FLOOR", disordered.get(1).componentType());
        assertEquals("ROOF", disordered.get(2).componentType());
        assertEquals("FACADE", disordered.get(3).componentType());
        assertEquals("ENTRANCE", disordered.get(4).componentType());
        assertEquals("FACADE_WINDOWS", disordered.get(5).componentType());
        assertEquals("DECOR_DETAIL", disordered.get(6).componentType());
    }

    private static Component component(String type, String slot) {
        return new Component(type, slot, new Vec3i(0, 0, 0), null, null, Map.of());
    }
}
