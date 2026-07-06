package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AssemblyPlanPromoterTest {

    @Test
    void promotesNestedAssemblyAndStripsConflictingComponents() {
        Map<String, Object> massParams = getStringObjectMap();

        List<Component> input = List.of(
                new Component(
                        "MASS_MAIN",
                        "tower_1",
                        new Vec3i(0, 0, 0),
                        new Dimensions(10, 10, 30),
                        List.of(),
                        massParams
                ),
                new Component(
                        "MASS_SECONDARY",
                        "tower_1",
                        new Vec3i(0, 0, 0),
                        new Dimensions(8, 8, 20),
                        List.of(),
                        Map.of()
                ),
                new Component(
                        "FACADE_WINDOWS",
                        "tower_1",
                        new Vec3i(0, 0, 0),
                        new Dimensions(10, 10, 30),
                        List.of(),
                        Map.of("window_ratio", 0.4)
                ),
                new Component(
                        "ROOF",
                        "tower_1",
                        new Vec3i(0, 0, 0),
                        new Dimensions(10, 10, 2),
                        List.of(),
                        Map.of("roof_type", "flat")
                )
        );

        AssemblyPlanPromoter.PromotionResult result = AssemblyPlanPromoter.promoteNestedAssembly(input);

        assertEquals(1, result.components().size());
        Component assemblyComponent = result.components().getFirst();
        assertEquals("ASSEMBLY", assemblyComponent.componentType());
        assertTrue(result.assemblyPrimarySlots().contains("tower_1"));
        assertTrue(assemblyComponent.params().containsKey("assembly"));

        Object nested = assemblyComponent.params().get("assembly");
        assertInstanceOf(Map.class, nested);
        @SuppressWarnings("unchecked")
        Map<String, Object> nestedMap = (Map<String, Object>) nested;
        assertTrue(nestedMap.containsKey("graph"));
    }

    private static @NotNull Map<String, Object> getStringObjectMap() {
        Map<String, Object> assembly = Map.of(
                "entranceFacing", "SOUTH",
                "graph", Map.of(
                        "components", List.of(
                                Map.of(
                                        "id", "Core",
                                        "type", "SHELL_BOX",
                                        "at", Map.of("x", 0, "y", 0, "z", 0),
                                        "w", 10, "d", 10, "h", 24,
                                        "twistTurns", 0.75
                                )
                        ),
                        "connections", List.of()
                )
        );

        Map<String, Object> massParams = new HashMap<>();
        massParams.put("shape", "circle");
        massParams.put("void_ratio", 0.3);
        massParams.put("assembly", assembly);
        return massParams;
    }

    @Test
    void stripsConflictingComponentsWhenExplicitAssemblyPresent() {
        Map<String, Object> assemblyParams = Map.of(
                "assembly", Map.of(
                        "graph", Map.of(
                                "components", List.of(
                                        Map.of("id", "Shell", "type", "SHELL_BOX", "w", 8, "d", 8, "h", 20)
                                ),
                                "connections", List.of()
                        )
                )
        );

        List<Component> input = List.of(
                new Component(
                        "ASSEMBLY",
                        "tower_1",
                        new Vec3i(0, 0, 0),
                        new Dimensions(10, 10, 24),
                        List.of(),
                        assemblyParams
                ),
                new Component(
                        "ROOF",
                        "tower_1",
                        new Vec3i(0, 0, 0),
                        new Dimensions(10, 10, 2),
                        List.of(),
                        Map.of("roof_type", "flat")
                ),
                new Component(
                        "MASS_MAIN",
                        "tower_1",
                        new Vec3i(0, 0, 0),
                        new Dimensions(10, 10, 24),
                        List.of(),
                        Map.of("shape", "circle")
                )
        );

        AssemblyPlanPromoter.PromotionResult result = AssemblyPlanPromoter.promoteNestedAssembly(input);

        assertEquals(1, result.components().size());
        assertEquals("ASSEMBLY", result.components().getFirst().componentType());
        assertTrue(result.assemblyPrimarySlots().contains("tower_1"));
    }

    @Test
    void prepareComponentsSkipsMassInferenceForPromotedAssembly() throws Exception {
        Map<String, Object> massParams = new HashMap<>();
        massParams.put("assembly", Map.of(
                "graph", Map.of(
                        "components", List.of(
                                Map.of(
                                        "id", "Shell",
                                        "type", "SHELL_BOX",
                                        "at", Map.of("x", 0, "y", 0, "z", 0),
                                        "w", 8, "d", 8, "h", 20
                                )
                        ),
                        "connections", List.of()
                )
        ));

        LlmPlan plan = new LlmPlan(
                LlmPlan.Mode.build,
                "Gothic_Cathedral",
                new Vec3i(0, 64, 0),
                new GlobalConstraints(GlobalConstraints.Facing.SOUTH, null, null),
                new Layout(null, false, List.of(
                        new Slot("tower_1", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, "CIVIC", null, null)
                )),
                List.of(new Component(
                        "MASS_MAIN",
                        "tower_1",
                        new Vec3i(0, 0, 0),
                        new Dimensions(10, 10, 24),
                        List.of(),
                        massParams
                )),
                null, null, null, null, null, null, null, null, null, null, null, null, null
        );

        @SuppressWarnings("unchecked")
        List<Component> prepared = invokePrepareComponents(plan);

        assertEquals(1, prepared.size());
        assertEquals("ASSEMBLY", prepared.getFirst().componentType());
        assertFalse(prepared.stream().anyMatch(c -> "MASS_MAIN".equals(c.componentType())));
        assertFalse(prepared.stream().anyMatch(c -> "FACADE_WINDOWS".equals(c.componentType())));
        assertFalse(prepared.stream().anyMatch(c -> "ROOF".equals(c.componentType())));
    }

    @SuppressWarnings("unchecked")
    private static List<Component> invokePrepareComponents(LlmPlan plan) throws Exception {
        Method m = ComponentPlanCompiler.class.getDeclaredMethod(
                "prepareComponents",
                LlmPlan.class,
                Map.class,
                boolean.class
        );
        m.setAccessible(true);
        Object result = m.invoke(null, plan, Map.of("tower_1", plan.layout().slots().getFirst()), false);
        Method componentsGetter = result.getClass().getDeclaredMethod("components");
        return (List<Component>) componentsGetter.invoke(result);
    }
}
