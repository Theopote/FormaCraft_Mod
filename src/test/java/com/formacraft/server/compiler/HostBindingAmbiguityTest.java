package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HostBindingAmbiguityTest {
    @AfterEach
    void clear() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void unboundSatelliteBetweenTwoMassesIsAmbiguous() {
        Component massA = new Component("MASS_MAIN", "s0", new Vec3i(0, 0, 0),
                new Dimensions(5, 5, 5), null, Map.of("component_id", "a"));
        Component massB = new Component("MASS_MAIN", "s0", new Vec3i(10, 0, 0),
                new Dimensions(5, 5, 5), null, Map.of("component_id", "b"));
        // Centers at 2 and 12; roof at x=7 → equidistant
        Component roof = new Component("ROOF", "s0", new Vec3i(7, 5, 0),
                new Dimensions(5, 5, 2), null, Map.of("component_id", "roof", "roof_type", "flat"));
        List<Component> primary = List.of(massA, massB, roof);

        assertEquals(ComponentPlanCompiler.HostPick.AMBIGUOUS,
                ComponentPlanCompiler.resolveUnboundHost(massA, primary, roof));

        Method find;
        try {
            find = ComponentPlanCompiler.class.getDeclaredMethod(
                    "findBoundSatellite", Component.class, List.class, List.class, String.class);
            find.setAccessible(true);
            find.invoke(null, massA, primary, List.of(), "ROOF");
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_HOST_BINDING_AMBIGUOUS", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void explicitHostIdBindsWithoutAmbiguity() throws Exception {
        Method prepare = ComponentPlanCompiler.class.getDeclaredMethod(
                "prepareComponents", LlmPlan.class, Map.class, boolean.class);
        prepare.setAccessible(true);
        AssemblyCompileDiagnostics.clear();

        Component massA = new Component("MASS_MAIN", "s0", new Vec3i(0, 0, 0),
                new Dimensions(6, 6, 5), null, Map.of("component_id", "a"));
        Component massB = new Component("MASS_MAIN", "s0", new Vec3i(10, 0, 0),
                new Dimensions(6, 6, 5), null, Map.of("component_id", "b"));
        Component roof = new Component("ROOF", "s0", new Vec3i(0, 5, 0),
                new Dimensions(6, 6, 2), null,
                Map.of("component_id", "roof", "host_id", "a", "roof_type", "flat"));

        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .components(List.of(massA, massB, roof))
                .layout(new Layout(null, false, List.of(new Slot("s0", new Vec3i(0, 64, 0), null, null, null, null))))
                .build();

        Object prepared = prepare.invoke(null, plan, Map.of(), false);
        assertFalse(AssemblyCompileDiagnostics.hasGap());
        @SuppressWarnings("unchecked")
        List<Component> components = (List<Component>) prepared.getClass().getRecordComponents()[0].getAccessor().invoke(prepared);
        Component outA = components.stream()
                .filter(c -> "MASS_MAIN".equalsIgnoreCase(c.componentType())
                        && "a".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals(true, outA.params().get("compiler_suppressed_roof"));
    }
}
