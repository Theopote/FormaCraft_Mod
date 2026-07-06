package com.formacraft.server.compiler;

import com.formacraft.common.alignment.AlignmentAndSymmetry;
import com.formacraft.common.alignment.BayRhythm;
import com.formacraft.common.alignment.BaySpec;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentPlanCompilerAlignmentTest {

    @Test
    void prepareComponentsAppliesAlignmentBeforeRealign() throws Exception {
        AlignmentAndSymmetry contract = new AlignmentAndSymmetry(
                "bilateral_z",
                null,
                0,
                null,
                new BayRhythm(
                        List.of(new BaySpec(4, "wing"), new BaySpec(6, "center"), new BaySpec(4, "wing")),
                        null,
                        null
                )
        );
        LlmPlan plan = new LlmPlan(
                LlmPlan.Mode.build,
                "Neoclassical",
                new Vec3i(0, 64, 0),
                new GlobalConstraints(GlobalConstraints.Facing.SOUTH, null, null),
                new Layout(null, false, List.of(
                        new Slot("main", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, "RESIDENTIAL", null, null)
                )),
                List.of(new Component(
                        "MASS_MAIN",
                        "main",
                        new Vec3i(0, 0, 0),
                        new Dimensions(20, 10, 30),
                        List.of(),
                        Map.of("anchor_mode", "center")
                )),
                null,
                null,
                null,
                contract,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        @SuppressWarnings("unchecked")
        List<Component> prepared = invokePrepareComponents(plan);

        Component mass = prepared.stream()
                .filter(c -> "MASS_MAIN".equals(c.componentType()))
                .findFirst()
                .orElseThrow();
        assertEquals(14, mass.dimensions().depth());
        assertTrue(Boolean.TRUE.equals(mass.params().get("alignment_contract_applied")));
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
        Object result = m.invoke(null, plan, Map.of("main", plan.layout().slots().getFirst()), true);
        Method componentsGetter = result.getClass().getDeclaredMethod("components");
        return (List<Component>) componentsGetter.invoke(result);
    }
}
