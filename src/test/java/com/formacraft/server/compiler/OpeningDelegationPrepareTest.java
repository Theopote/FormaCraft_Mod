package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpeningDelegationPrepareTest {
    @Test void wallFreeOneLayerPavilionBaseDoesNotGrowFacade() throws Exception {
        Method prepare=ComponentPlanCompiler.class.getDeclaredMethod("prepareComponents",LlmPlan.class,Map.class,boolean.class);
        prepare.setAccessible(true);
        var floor=new Component("MASS_MAIN","s",new Vec3i(0,0,0),new Dimensions(5,5,1),List.of(),
                Map.of("component_id","pavilion_base","wall_type","none","floor_height",1));
        var plan=LlmPlanTestFixtures.builder().components(List.of(floor)).build();
        Object result=prepare.invoke(null,plan,Map.of(),false);
        @SuppressWarnings("unchecked") var parts=(List<Component>)result.getClass().getRecordComponents()[0].getAccessor().invoke(result);
        var base=parts.stream().filter(c->"MASS_MAIN".equals(c.componentType())).findFirst().orElseThrow();
        assertEquals(1,base.dimensions().height());
        assertEquals("plate",base.params().get("extrude_mode"));
        assertFalse(parts.stream().anyMatch(c->"FACADE_WINDOWS".equals(c.componentType())));
    }
    @Test
    void partialFacadePreservesAuthoredSuppressionOfHostDefaults() throws Exception {
        Method prepare=ComponentPlanCompiler.class.getDeclaredMethod("prepareComponents",LlmPlan.class,Map.class,boolean.class);
        prepare.setAccessible(true);
        var mass=new Component("MASS_MAIN","s0",new Vec3i(0,0,0),new Dimensions(8,8,6),null,
                Map.of("component_id","b1","suppress_windows",true,"window_ratio",.18));
        var window=new Component("FACADE_WINDOWS","s0",new Vec3i(0,1,7),new Dimensions(8,1,4),null,
                Map.of("component_id","north_window","host_id","b1","faces",List.of("NORTH")));
        var plan=LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).components(List.of(mass,window))
                .layout(new Layout(null,false,List.of(new Slot("s0",new Vec3i(0,64,0),GlobalConstraints.Facing.SOUTH,null,null,null)))).build();
        Object prepared=prepare.invoke(null,plan,Map.of(),false);
        @SuppressWarnings("unchecked") var components=(List<Component>)prepared.getClass().getRecordComponents()[0].getAccessor().invoke(prepared);
        var host=components.stream().filter(c->"MASS_MAIN".equals(c.componentType())).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE,host.params().get("suppress_windows"));
        assertTrue(components.stream().anyMatch(c->"FACADE_WINDOWS".equals(c.componentType())),"explicit facade remains allowed");
    }

    @BeforeEach
    @AfterEach
    void clear() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void fullPlusPartialSatelliteYieldsFullWindowSuppress() throws Exception {
        Method prepare = ComponentPlanCompiler.class.getDeclaredMethod(
                "prepareComponents", LlmPlan.class, Map.class, boolean.class);
        prepare.setAccessible(true);

        Component mass = new Component("MASS_MAIN", "s0", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 6), null, Map.of("component_id", "b1"));
        Component fullWin = new Component("FACADE_WINDOWS", "s0", new Vec3i(0, 1, 0),
                new Dimensions(8, 1, 4), null, Map.of("component_id", "w_full", "host_id", "b1"));
        Component northWin = new Component("FACADE_WINDOWS", "s0", new Vec3i(0, 1, 7),
                new Dimensions(8, 1, 4), null,
                Map.of("component_id", "w_n", "host_id", "b1", "faces", "NORTH"));

        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .components(List.of(mass, fullWin, northWin))
                .layout(new Layout(null, false, List.of(new Slot("s0", new Vec3i(0, 64, 0), null, null, null, null))))
                .build();

        Object prepared = prepare.invoke(null, plan, Map.of(), false);
        @SuppressWarnings("unchecked")
        List<Component> components = (List<Component>) prepared.getClass().getRecordComponents()[0].getAccessor().invoke(prepared);
        Component outMass = components.stream()
                .filter(c -> "MASS_MAIN".equalsIgnoreCase(c.componentType()))
                .findFirst().orElseThrow();
        assertEquals(true, outMass.params().get("suppress_windows"));
        assertNull(outMass.params().get("suppress_window_faces"));
    }

    @Test
    void entranceOnlyDoesNotSuppressWindows() throws Exception {
        Method prepare = ComponentPlanCompiler.class.getDeclaredMethod(
                "prepareComponents", LlmPlan.class, Map.class, boolean.class);
        prepare.setAccessible(true);

        Component mass = new Component("MASS_MAIN", "s0", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 6), null,
                Map.of("component_id", "b1", "window_style", "none")); // disable auto window infer noise
        // Re-enable windows on mass but provide entrance satellite only
        mass = new Component("MASS_MAIN", "s0", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 6), null, Map.of("component_id", "b1"));
        Component entrance = new Component("ENTRANCE", "s0", new Vec3i(3, 0, 0),
                new Dimensions(3, 1, 3), null, Map.of("component_id", "e1", "host_id", "b1"));
        // Explicit facade windows with faces so we don't auto-infer full facade — wait, without
        // FACADE_WINDOWS, prepare will infer facade → full window suppress. Provide a partial
        // FACADE_WINDOWS so windows are not fully suppressed while entrance is full.
        Component northWin = new Component("FACADE_WINDOWS", "s0", new Vec3i(0, 1, 7),
                new Dimensions(8, 1, 4), null,
                Map.of("component_id", "w_n", "host_id", "b1", "faces", List.of("NORTH")));

        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .components(List.of(mass, entrance, northWin))
                .layout(new Layout(null, false, List.of(
                        new Slot("s0", new Vec3i(0, 64, 0), GlobalConstraints.Facing.SOUTH, null, null, null))))
                .build();

        Object prepared = prepare.invoke(null, plan, Map.of(), false);
        @SuppressWarnings("unchecked")
        List<Component> components = (List<Component>) prepared.getClass().getRecordComponents()[0].getAccessor().invoke(prepared);
        Component outMass = components.stream()
                .filter(c -> "MASS_MAIN".equalsIgnoreCase(c.componentType()))
                .findFirst().orElseThrow();
        assertEquals(true, outMass.params().get("suppress_doors"));
        assertNotEquals(Boolean.TRUE, outMass.params().get("suppress_windows"));
        assertNotNull(outMass.params().get("suppress_window_faces"));
    }
}
