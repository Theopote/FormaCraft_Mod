package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoofGeneratorPitchFidelityTest {

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
}
