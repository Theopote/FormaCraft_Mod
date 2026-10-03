package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.test.PatchTestSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RoofAttachmentTest {
    @Test
    void flatPlateStaysAtAttachmentPlaneRegardlessOfPitchBudget() {
        for (int height : List.of(1, 4)) {
            Map<Vec3i, String> roof = generate(height, Map.of("roof_type", "flat"));
            assertEquals(9 * 7, roof.size());
            for (int x = -4; x < 5; x++) {
                for (int z = 8; z < 15; z++) {
                    assertNotNull(roof.get(new Vec3i(x, 15, z)), "missing flat plate cell");
                }
            }
            assertTrue(roof.keySet().stream().allMatch(p -> p.y() == 15));
        }
    }

    @Test
    void pitchedRoofRespectsDimensionsHeightInBlockLayers() {
        Map<Vec3i, String> roof = generate(4, Map.of("roof_type", "gable"));
        assertEquals(15, roof.keySet().stream().mapToInt(Vec3i::y).min().orElseThrow());
        assertEquals(18, roof.keySet().stream().mapToInt(Vec3i::y).max().orElseThrow());
    }

    @Test
    void explicitRoofHeightOverridesDimensionsHeight() {
        Map<Vec3i, String> roof = generate(2, Map.of("roof_type", "gable", "roof_height", 5));
        assertEquals(19, roof.keySet().stream().mapToInt(Vec3i::y).max().orElseThrow());
    }

    private static Map<Vec3i, String> generate(int height, Map<String, Object> params) {
        Component component = new Component("ROOF", null, new Vec3i(-4, 15, 8),
                new Dimensions(9, 7, height), List.of(), params);
        return PatchTestSnapshot.blocks(new RoofGenerator().generate(
                new SemanticComponent("ROOF", null, component, "DEFAULT")));
    }
}
