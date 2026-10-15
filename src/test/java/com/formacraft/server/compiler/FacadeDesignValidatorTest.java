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

class FacadeDesignValidatorTest {

    @Test
    void rejectsExplicitSquareWithArrowSlitStyle() throws Exception {
        var plan = LlmPlanParser.parseAndValidate(loadFixture("window_aspect_style_conflict.json"));
        var gap = FacadeDesignValidator.check(plan, plan.components());
        assertTrue(gap.isPresent());
        assertEquals("E_WINDOW_ASPECT_CONFLICT", gap.get().code());
    }

    @Test
    void rejectsFloorLayoutOverflow() throws Exception {
        var plan = LlmPlanParser.parseAndValidate(loadFixture("floor_layout_overflow.json"));
        var gap = FacadeDesignValidator.check(plan, plan.components());
        assertTrue(gap.isPresent());
        assertEquals("E_FLOOR_LAYOUT_OVERFLOW", gap.get().code());
    }

    @Test
    void acceptsCompatibleExplicitAspect() {
        Component facade = new Component(
                "FACADE_WINDOWS",
                "body",
                new Vec3i(0, 0, 0),
                new Dimensions(14, 12, 10),
                List.of("large"),
                Map.of("window_aspect", "square", "window_ratio", 0.2));
        var plan = LlmPlanTestFixtures.builder().components(List.of(facade)).build();
        assertTrue(FacadeDesignValidator.check(plan, List.of(facade)).isEmpty());
    }

    @Test
    void inheritedFloorMetadataDoesNotTurnSlabOrConnectorIntoBuilding() {
        for (var params : List.of(
                Map.<String, Object>of("extrude_mode", "plate", "floor_count", 3, "floor_height", 4),
                Map.<String, Object>of("compiler_connector_body", true, "floor_count", 3, "floor_height", 4))) {
            var slab = new Component("MASS_SECONDARY", "body", new Vec3i(0,4,0),
                    new Dimensions(15,13,1), List.of(), params);
            var plan = LlmPlanTestFixtures.builder().components(List.of(slab)).build();
            assertTrue(FacadeDesignValidator.check(plan, List.of(slab)).isEmpty());
        }
    }

    private static String loadFixture(String fileName) throws Exception {
        String path = "/plans/fidelity/" + fileName;
        try (var in = FacadeDesignValidatorTest.class.getResourceAsStream(path)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
