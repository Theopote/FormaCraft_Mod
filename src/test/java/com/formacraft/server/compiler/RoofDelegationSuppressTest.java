package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RoofDelegationSuppressTest {
    @org.junit.jupiter.api.BeforeEach void clearDiagnostics() {
        com.formacraft.server.assembly.AssemblyCompileDiagnostics.clear();
    }
    @Test
    void explicitRoofSuppressesMassBuiltinRoof() throws Exception {
        Method prepare = ComponentPlanCompiler.class.getDeclaredMethod(
                "prepareComponents", LlmPlan.class, Map.class, boolean.class);
        prepare.setAccessible(true);

        Component mass = new Component("MASS_MAIN", "s0", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 6), null,
                Map.of("component_id", "mass1", "roof_type", "gable"));
        Component roof = new Component("ROOF", "s0", new Vec3i(0, 6, 0),
                new Dimensions(8, 8, 3), null,
                Map.of("component_id", "roof1", "host_id", "mass1", "roof_type", "gable"));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .styleProfile("DEFAULT")
                .components(List.of(mass, roof))
                .layout(new Layout(null, false, List.of(new Slot("s0", new Vec3i(0, 64, 0), null, null, null, null))))
                .build();

        Object prepared = prepare.invoke(null, plan, Map.of(), false);
        @SuppressWarnings("unchecked")
        List<Component> components = (List<Component>) prepared.getClass().getRecordComponents()[0].getAccessor().invoke(prepared);
        Component outMass = components.stream()
                .filter(c -> "MASS_MAIN".equalsIgnoreCase(c.componentType()))
                .findFirst().orElseThrow();
        assertEquals(true, outMass.params().get("compiler_suppressed_roof"));
        assertEquals("none", String.valueOf(outMass.params().get("roof_type")));
    }

    @Test
    void softGenerationFailureOnlyForDecor() {
        assertTrue(ComponentPlanCompiler.isSoftGenerationFailure(
                new Component("DECOR_DETAIL", "d", new Vec3i(0, 0, 0), null, null, Map.of())));
        assertFalse(ComponentPlanCompiler.isSoftGenerationFailure(
                new Component("ROOF", "r", new Vec3i(0, 0, 0), null, null, Map.of())));
        assertFalse(ComponentPlanCompiler.isSoftGenerationFailure(
                new Component("MASS_MAIN", "m", new Vec3i(0, 0, 0), null, null, Map.of())));
    }

    @Test
    void parseFaceNamesSupportsCsvAndList() {
        assertEquals(java.util.Set.of("NORTH", "SOUTH"),
                OpeningDelegation.parseFaceNames(Map.of("faces", "NORTH,SOUTH")));
        assertEquals(java.util.Set.of("EAST"),
                OpeningDelegation.parseFaceNames(Map.of("suppress_faces", List.of("EAST"))));
        // facing alone is door orientation, not a suppress face list
        assertTrue(OpeningDelegation.parseFaceNames(Map.of("facing", "SOUTH")).isEmpty());
        assertTrue(OpeningDelegation.parseFaceNames(Map.of()).isEmpty());
    }
}
