package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class DesignValidatorSuiteTest {
    @BeforeAll static void bootstrap() { MinecraftRegistryTestBootstrap.initialize(); }
    @AfterEach void clearDiagnostics() { AssemblyCompileDiagnostics.clear(); }

    private static Component roof() {
        return new Component("ROOF", "r", new Vec3i(0, 8, 0), new Dimensions(12, 10, 2),
                List.of(), Map.of("roof_type", "unsupported_suite_roof"));
    }

    private static Component invalidFacade() {
        return new Component("FACADE_WINDOWS", "body", new Vec3i(0, 0, 0), new Dimensions(14, 12, 10),
                List.of(), Map.of("window_aspect", "square", "window_style", "arrow_slit"));
    }

    @Test void roofFailureWinsOverFacadeFailureRegardlessOfComponentOrder() {
        var components = List.of(invalidFacade(), roof());
        var plan = LlmPlanTestFixtures.builder().components(components).build();
        assertEquals("E_WINDOW_ASPECT_CONFLICT", FacadeDesignValidator.check(plan, components).orElseThrow().code());
        assertEquals("E_ROOF_TYPE_UNSUPPORTED", DesignValidatorSuite.checkAuthored(plan).orElseThrow().code());
        assertEquals("E_ROOF_TYPE_UNSUPPORTED", DesignValidatorSuite.checkPrepared(plan, components).orElseThrow().code());
    }

    @Test void preparedPassChecksPreparedSnapshotRatherThanAuthoredComponents() {
        var plan = LlmPlanTestFixtures.builder().components(List.of()).build();
        assertTrue(DesignValidatorSuite.checkAuthored(plan).isEmpty());
        assertEquals("E_ROOF_TYPE_UNSUPPORTED",
                DesignValidatorSuite.checkPrepared(plan, List.of(roof())).orElseThrow().code());
    }

    @Test void authorityCheckStaysInPreparedPassAndPrecedesPreparedDesign() {
        var components = List.of(roof());
        var plan = LlmPlanTestFixtures.builder().components(components)
                .planMeta(Map.of("generation_authority", "invalid_suite_authority")).build();
        assertEquals("E_ROOF_TYPE_UNSUPPORTED", DesignValidatorSuite.checkAuthored(plan).orElseThrow().code());
        assertEquals("E_INVALID_GENERATION_AUTHORITY",
                DesignValidatorSuite.checkPrepared(plan, components).orElseThrow().code());
    }

    @Test void materialChecksRetainStageSpecificPathsAndMessages() {
        var attributes = new StyleAttributes(null, "suite:missing_wall", null, null, null, null, null, null);
        var authored = LlmPlanTestFixtures.builder().components(List.of()).styleAttributes(attributes).build();
        var attributeGap = DesignValidatorSuite.checkAuthored(authored).orElseThrow();
        assertEquals("E_MATERIAL_INVALID", attributeGap.code());
        assertEquals("style_attributes", attributeGap.path());
        assertTrue(attributeGap.message().startsWith("无法解析明确指定的材料："));

        var component = new Component("DECOR_DETAIL", "s", new Vec3i(0, 0, 0), new Dimensions(1, 1, 1),
                List.of(), Map.of("wall_block", "suite:missing_wall"));
        var plan = LlmPlanTestFixtures.builder().components(List.of(component)).build();
        assertTrue(DesignValidatorSuite.checkAuthored(plan).isEmpty(), "component material gate belongs to prepared pass");
        var componentGap = DesignValidatorSuite.checkPrepared(plan, plan.components()).orElseThrow();
        assertEquals("E_MATERIAL_INVALID", componentGap.code());
        assertEquals("components[]", componentGap.path());
        assertTrue(componentGap.message().startsWith("无法解析明确指定的构件材料："));
    }

    @Test void suiteDoesNotPublishOrClearCallerDiagnostics() {
        var existing = new CapabilityGap("EXISTING", "caller owned", "plan", List.of());
        AssemblyCompileDiagnostics.set(existing);
        AssemblyCompileDiagnostics.addSoftWarning("EXISTING_WARNING", "keep", null, null);
        var warnings = AssemblyCompileDiagnostics.softWarnings();
        var plan = LlmPlanTestFixtures.builder().components(List.of(roof())).build();
        assertTrue(DesignValidatorSuite.checkAuthored(plan).isPresent());
        assertTrue(DesignValidatorSuite.checkPrepared(plan, plan.components()).isPresent());
        assertSame(existing, AssemblyCompileDiagnostics.get());
        assertEquals(warnings, AssemblyCompileDiagnostics.softWarnings());
    }

    @Test void existingDesignFailuresAreRetainedInBothPasses() throws Exception {
        for (var fixture : Map.of(
                "conflicting_roof_height_pitch.json", "E_ROOF_GEOMETRY_CONFLICT",
                "window_aspect_style_conflict.json", "E_WINDOW_ASPECT_CONFLICT",
                "floor_layout_overflow.json", "E_FLOOR_LAYOUT_OVERFLOW",
                "courtyard_ratio_unfit.json", "E_COURTYARD_GEOMETRY_CONFLICT" ).entrySet()) {
            try (var in = getClass().getResourceAsStream("/plans/fidelity/" + fixture.getKey())) {
                var plan = LlmPlanParser.parseAndValidate(new String(
                        Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8));
                assertEquals(fixture.getValue(), DesignValidatorSuite.checkAuthored(plan).orElseThrow().code(), fixture.getKey());
                assertEquals(fixture.getValue(), DesignValidatorSuite.checkPrepared(plan, plan.components()).orElseThrow().code(), fixture.getKey());
            }
        }
    }

    @Test void compilerStillPublishesAuthoredFailureAndReturnsNoPatches() {
        var plan = LlmPlanTestFixtures.builder().components(List.of(roof())).build();
        assertTrue(ComponentPlanCompiler.compile(plan).isEmpty());
        assertEquals("E_ROOF_TYPE_UNSUPPORTED", AssemblyCompileDiagnostics.get().code());
    }
}
