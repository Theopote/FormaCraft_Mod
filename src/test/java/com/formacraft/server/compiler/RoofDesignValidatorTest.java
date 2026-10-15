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

class RoofDesignValidatorTest {

    @Test
    void rejectsUnsupportedExplicitRoofType() throws Exception {
        var plan = LlmPlanParser.parseAndValidate(loadFixture("unknown_roof_type.json"));
        var gap = RoofDesignValidator.check(plan, plan.components());
        assertTrue(gap.isPresent());
        assertEquals("E_ROOF_TYPE_UNSUPPORTED", gap.get().code());
    }

    @Test
    void rejectsConflictingRoofHeightAndPitch() throws Exception {
        var plan = LlmPlanParser.parseAndValidate(loadFixture("conflicting_roof_height_pitch.json"));
        var gap = RoofDesignValidator.check(plan, plan.components());
        assertTrue(gap.isPresent());
        assertEquals("E_ROOF_GEOMETRY_CONFLICT", gap.get().code());
    }

    @Test
    void rejectsInvalidRoofHeightAndPitch() throws Exception {
        var plan = LlmPlanParser.parseAndValidate(loadFixture("invalid_roof_height_pitch.json"));
        var gap = RoofDesignValidator.check(plan, plan.components());
        assertTrue(gap.isPresent());
        assertEquals("E_ROOF_GEOMETRY_INVALID", gap.get().code());
    }

    @Test
    void acceptsKnownRoofType() {
        Component roof = new Component(
                "ROOF",
                "r",
                new Vec3i(0, 8, 0),
                new Dimensions(12, 10, 2),
                List.of(),
                Map.of("roof_type", "gable"));
        var plan = LlmPlanTestFixtures.builder().components(List.of(roof)).build();
        assertTrue(RoofDesignValidator.check(plan, List.of(roof)).isEmpty());
    }

    private static String loadFixture(String fileName) throws Exception {
        String path = "/plans/fidelity/" + fileName;
        try (var in = RoofDesignValidatorTest.class.getResourceAsStream(path)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
