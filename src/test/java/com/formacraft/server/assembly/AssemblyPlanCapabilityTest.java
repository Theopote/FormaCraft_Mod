package com.formacraft.server.assembly;

import com.formacraft.common.llm.dto.LlmPlanTestFixtures;

import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssemblyPlanCapabilityTest {

    @AfterEach
    void tearDown() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void detectsAssemblyOnlyPlan() {
        LlmPlan plan = assemblyOnlyPlan();
        assertTrue(AssemblyPlanCapability.isAssemblyOnly(plan));
    }

    @Test
    void rejectsMixedMassAndAssemblyPlan() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .styleProfile("Gothic_Cathedral")
                .anchor(new Vec3i(0, 64, 0))
                .components(List.of(
                        assemblyComponent(),
                        new Component(
                                "MASS_MAIN",
                                null,
                                new Vec3i(0, 0, 0),
                                new Dimensions(10, 10, 12),
                                List.of(),
                                Map.of()
                        )
                ))
                .build();

        assertFalse(AssemblyPlanCapability.isAssemblyOnly(plan));
    }

    @Test
    void llmPlanHasCapabilityGapWhenStatusSet() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .anchor(new Vec3i(0, 64, 0))
                .components(List.of())
                .planStatus("capability_gap")
                .error("unsupported geometry")
                .capabilityGap(new CapabilityGap("E_TEST", "unsupported geometry", "plan", List.of("use preset")))
                .build();

        assertTrue(plan.hasCapabilityGap());
    }

    @Test
    void compileDiagnosticsRoundTrip() {
        CapabilityGap gap = new CapabilityGap(
                "E_ASSEMBLY_MISSING",
                "missing assembly payload",
                "components[0].params.assembly",
                List.of("use preset"));
        AssemblyCompileDiagnostics.set(gap);
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertNotNull(AssemblyCompileDiagnostics.get());
        assertEquals("E_ASSEMBLY_MISSING", AssemblyCompileDiagnostics.get().code());
        AssemblyCompileDiagnostics.clear();
        assertNull(AssemblyCompileDiagnostics.get());
    }

    private static LlmPlan assemblyOnlyPlan() {
        return LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .styleProfile("Gothic_Cathedral")
                .anchor(new Vec3i(0, 64, 0))
                .globalConstraints(new GlobalConstraints(GlobalConstraints.Facing.SOUTH, null, null))
                .layout(new Layout(null, false, List.of()))
                .components(List.of(assemblyComponent()))
                .build();

    }

    private static Component assemblyComponent() {
        return new Component(
                "ASSEMBLY",
                null,
                new Vec3i(0, 0, 0),
                new Dimensions(10, 10, 24),
                List.of(),
                Map.of("assembly", Map.of("preset", "spiral_watchtower", "presetParams", Map.of()))
        );
    }
}
