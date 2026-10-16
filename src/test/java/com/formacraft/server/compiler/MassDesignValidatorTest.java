package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.llm.parser.LlmPlanParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MassDesignValidatorTest {

    @Test
    void rejectsUnfitCourtyardRatio() throws Exception {
        var plan = LlmPlanParser.parseAndValidate(loadFixture("courtyard_ratio_unfit.json"));
        var gap = MassDesignValidator.check(plan, plan.components());
        assertTrue(gap.isPresent());
        assertEquals("E_COURTYARD_GEOMETRY_CONFLICT", gap.get().code());
    }

    @Test
    void acceptsLargeCourtyardRatio() throws Exception {
        var plan = LlmPlanParser.parseAndValidate(loadFixture("courtyard_ratio_075.json"));
        assertTrue(MassDesignValidator.check(plan, plan.components()).isEmpty());
    }

    @Test
    void rejectsWallThicknessThatFillsEnvelope() {
        Component mass = new Component(
                "MASS_MAIN",
                "body",
                new Vec3i(0, 0, 0),
                new Dimensions(10, 10, 8),
                List.of(),
                Map.of("wall_thickness", 5));
        var plan = LlmPlanTestFixtures.builder().components(List.of(mass)).build();
        var gap = MassDesignValidator.check(plan, List.of(mass));
        assertTrue(gap.isPresent());
        assertEquals("E_WALL_THICKNESS_UNFIT", gap.get().code());
    }

    @Test
    void allowsThickWallWhenSolid() {
        Component mass = new Component(
                "MASS_MAIN",
                "body",
                new Vec3i(0, 0, 0),
                new Dimensions(10, 10, 8),
                List.of(),
                Map.of("wall_thickness", 5, "void_ratio", 0.0));
        var plan = LlmPlanTestFixtures.builder().components(List.of(mass)).build();
        assertTrue(MassDesignValidator.check(plan, List.of(mass)).isEmpty());
    }

    @Test
    void rejectsOversizedPlanPatternCut() {
        Component mass = new Component(
                "MASS_MAIN",
                "body",
                new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 6),
                List.of(),
                Map.of("plan_type", "l_shape", "l_cut", 10));
        var plan = LlmPlanTestFixtures.builder().components(List.of(mass)).build();
        var gap = MassDesignValidator.check(plan, List.of(mass));
        assertTrue(gap.isPresent());
        assertEquals("E_PLAN_PATTERN_UNFIT", gap.get().code());
    }

    @Test
    void rejectsNegativeCourtyardRatioAsInvalid() throws Exception {
        var plan = LlmPlanParser.parseAndValidate(loadFixture("courtyard_ratio_negative.json"));
        var gap = MassDesignValidator.check(plan, plan.components());
        assertTrue(gap.isPresent());
        assertEquals("E_COURTYARD_RATIO_INVALID", gap.get().code());
    }

    @Test
    void rejectsNegativeWallThicknessAsInvalid() throws Exception {
        var plan = LlmPlanParser.parseAndValidate(loadFixture("wall_thickness_negative.json"));
        var gap = MassDesignValidator.check(plan, plan.components());
        assertTrue(gap.isPresent());
        assertEquals("E_WALL_THICKNESS_INVALID", gap.get().code());
    }

    @Test
    void rejectsNonNumericCourtyardRatioAsInvalid() {
        Component mass = new Component(
                "MASS_MAIN",
                "body",
                new Vec3i(0, 0, 0),
                new Dimensions(16, 16, 8),
                List.of("courtyard"),
                Map.of("plan_type", "courtyard", "courtyard_ratio", "wide"));
        var plan = LlmPlanTestFixtures.builder().components(List.of(mass)).build();
        var gap = MassDesignValidator.check(plan, List.of(mass));
        assertTrue(gap.isPresent());
        assertEquals("E_COURTYARD_RATIO_INVALID", gap.get().code());
    }

    private static String loadFixture(String fileName) throws Exception {
        String path = "/plans/fidelity/" + fileName;
        try (var in = MassDesignValidatorTest.class.getResourceAsStream(path)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
