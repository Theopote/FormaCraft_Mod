package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Vec3i;
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

        List<Component> disordered = new ArrayList<>(List.of(
                component("DECOR_DETAIL", "d"),
                component("ENTRANCE", "e"),
                component("MASS_MAIN", "m"),
                component("FACADE", "f"),
                component("ROOF", "r"),
                component("FLOOR", "fl"),
                component("FACADE_WINDOWS", "w")
        ));
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

        assertTrue((int) priority.invoke(null, component("MASS_MAIN", "a"))
                < (int) priority.invoke(null, component("FACADE_WINDOWS", "b")));
        assertTrue((int) priority.invoke(null, component("FACADE_WINDOWS", "b"))
                < (int) priority.invoke(null, component("DECOR_DETAIL", "c")));
    }

    private static Component component(String type, String slot) {
        return new Component(type, slot, new Vec3i(0, 0, 0), null, null, Map.of());
    }
}
