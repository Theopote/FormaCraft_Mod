package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.util.RoofTypeCatalog;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.proportion.RoofGrammarResolver;
import com.formacraft.common.style.StyleIntentResolver;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoofGeneratorPitchFidelityTest {
    @Test
    void shedRoofHasOneContinuousSlopeRatherThanFlatOrGable() {
        var roof = new Component("ROOF", "r", new Vec3i(0,8,0), new Dimensions(7,11,4),
                List.of(), Map.of("roof_type","shed","roof_height",4,"roof_block","minecraft:stone_bricks"));
        var patches = new RoofGenerator().generate(new SemanticComponent("ROOF",null,roof));
        assertEquals(77,patches.size());
        int previous = -1;
        for (int x=0;x<7;x++) {
            final int column=x;
            var heights = patches.stream().filter(p -> p.dx()==column).map(BlockPatch::dy).distinct().toList();
            assertEquals(1,heights.size());
            assertTrue(heights.getFirst()>=previous);
            previous=heights.getFirst();
        }
        assertEquals(11,previous);
        assertTrue(patches.stream().anyMatch(p -> p.dy()==8));
    }

    @Test
    void steepAiPlannedPitchProducesRiseAboveTechnicalCap() {
        Component roof = new Component(
                "ROOF",
                "r",
                new Vec3i(0, 10, 0),
                new Dimensions(30, 30, 2),
                List.of(),
                Map.of(
                        "roof_type", "gable",
                        "roof_pitch_degrees", 58,
                        "design_sources", Map.of(
                                "roof_type", "AI_PLANNED",
                                "roof_pitch_degrees", "AI_PLANNED")));
        List<BlockPatch> patches = new RoofGenerator().generate(new SemanticComponent("ROOF", null, roof));
        assertFalse(patches.isEmpty());
        int maxDy = patches.stream().mapToInt(BlockPatch::dy).max().orElse(0);
        int minDy = patches.stream().mapToInt(BlockPatch::dy).min().orElse(0);
        int rise = maxDy - minDy + 1;
        assertTrue(rise > 8, "expected steep gable rise > 8, got " + rise);
    }

    @Test
    void gothicFixturePreservesAiPlannedRoofTypeAndPitchThroughResolvers() throws Exception {
        LlmPlan plan = loadFixture("gothic_steep_roof.json");
        Component roof = plan.components().stream()
                .filter(c -> "ROOF".equalsIgnoreCase(c.componentType()))
                .findFirst()
                .orElseThrow();

        Component afterStyle = StyleIntentResolver.apply(plan, roof);
        assertEquals("gable", afterStyle.params().get("roof_type"));

        Component afterGrammar = RoofGrammarResolver.apply(plan, afterStyle);
        assertEquals(58, ((Number) afterGrammar.params().get("roof_pitch_degrees")).intValue());
    }

    @Test
    void unsupportedExplicitRoofTypeThrowsInsteadOfFlat() {
        Component roof = new Component(
                "ROOF",
                "r",
                new Vec3i(0, 8, 0),
                new Dimensions(12, 10, 2),
                List.of(),
                Map.of("roof_type", "unknown_roof_geometry"));
        assertThrows(IllegalArgumentException.class,
                () -> new RoofGenerator().generate(new SemanticComponent("ROOF", null, roof)));
    }

    @Test
    void roofTypeCatalogRecognizesSnakeCaseGable() {
        assertTrue(RoofTypeCatalog.parseExplicit("gable").isPresent());
        assertEquals("gable", RoofTypeCatalog.extractExplicitParamType(Map.of("roof_type", "gable")).orElseThrow());
    }

    private static LlmPlan loadFixture(String fileName) throws Exception {
        String path = "/plans/fidelity/" + fileName;
        try (var in = RoofGeneratorPitchFidelityTest.class.getResourceAsStream(path)) {
            return LlmPlanParser.parseAndValidate(new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
