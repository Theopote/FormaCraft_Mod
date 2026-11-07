package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructureAccessoryRemapRepairTest {

    @Test
    void remapsHaidenPlinthStructureToFoundation() {
        Component main = new Component(
                "STRUCTURE", "main", new Vec3i(0, 0, 0),
                new Dimensions(14, 12, 8), List.of("typology:japanese_haiden"),
                Map.of("component_id", "haiden_main", "typology_id", "japanese_haiden"));
        Component plinth = new Component(
                "STRUCTURE", "main", new Vec3i(-1, -1, -1),
                new Dimensions(16, 14, 1), List.of("台基"),
                Map.of("component_id", "haiden_plinth", "host_id", "haiden_main"));
        LlmPlan repaired = StructureAccessoryRemapRepair.apply(LlmPlanTestFixtures.builder()
                .components(List.of(main, plinth))
                .build());
        assertEquals("STRUCTURE", repaired.components().get(0).componentType());
        assertEquals("FOUNDATION", repaired.components().get(1).componentType());
        assertEquals("haiden_plinth", repaired.components().get(1).params().get("component_id"));
    }

    @Test
    void remapsApproachSteps() {
        Component steps = new Component(
                "STRUCTURE", "main", new Vec3i(4, 0, 12),
                new Dimensions(4, 6, 1), List.of(),
                Map.of("component_id", "haiden_steps"));
        LlmPlan repaired = StructureAccessoryRemapRepair.apply(LlmPlanTestFixtures.builder()
                .components(List.of(steps))
                .build());
        assertEquals("FOUNDATION", repaired.components().getFirst().componentType());
    }
}
