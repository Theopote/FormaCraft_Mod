package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DuplicateComponentIdRepairTest {

    @Test
    void rebindsRoofToRenamedMassWhenSlotAndFootprintMatch() {
        Component hallA = new Component(
                "MASS_MAIN", "slot_a", new Vec3i(0, 0, 0),
                new Dimensions(10, 10, 8), List.of(),
                Map.of("component_id", "hall", "anchor_mode", "min_corner"));
        Component hallB = new Component(
                "MASS_MAIN", "slot_b", new Vec3i(20, 0, 0),
                new Dimensions(10, 10, 8), List.of(),
                Map.of("component_id", "hall", "anchor_mode", "min_corner"));
        Component roofB = new Component(
                "ROOF", "slot_b", new Vec3i(19, 7, -1),
                new Dimensions(12, 12, 3), List.of(),
                Map.of("component_id", "hall_roof", "host_id", "hall",
                        "anchor_mode", "min_corner"));

        LlmPlan repaired = DuplicateComponentIdRepair.apply(LlmPlanTestFixtures.builder()
                .components(List.of(hallA, hallB, roofB))
                .build());

        Component renamed = repaired.components().stream()
                .filter(c -> "MASS_MAIN".equals(c.componentType())
                        && "slot_b".equals(c.slotId()))
                .findFirst().orElseThrow();
        assertEquals("hall_1", renamed.params().get("component_id"));
        assertEquals("hall", renamed.params().get("component_id_mapped_from"));

        Component roof = repaired.components().stream()
                .filter(c -> "hall_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals("hall_1", roof.params().get("host_id"),
                "roof on slot_b must follow the renamed second hall, not the keeper");
    }

    @Test
    void leavesDuplicatesWhenHostOwnershipIsAmbiguous() {
        Component hallA = new Component(
                "MASS_MAIN", "shared", new Vec3i(0, 0, 0),
                new Dimensions(10, 10, 8), List.of(),
                Map.of("component_id", "hall", "anchor_mode", "min_corner"));
        Component hallB = new Component(
                "MASS_MAIN", "shared", new Vec3i(12, 0, 0),
                new Dimensions(10, 10, 8), List.of(),
                Map.of("component_id", "hall", "anchor_mode", "min_corner"));
        // Roof sits between both halls on the shared slot with a neutral id — cannot choose.
        Component roof = new Component(
                "ROOF", "shared", new Vec3i(5, 7, -1),
                new Dimensions(12, 12, 3), List.of(),
                Map.of("component_id", "mystery_roof", "host_id", "hall",
                        "anchor_mode", "min_corner"));

        LlmPlan repaired = DuplicateComponentIdRepair.apply(LlmPlanTestFixtures.builder()
                .components(List.of(hallA, hallB, roof))
                .build());

        long halls = repaired.components().stream()
                .filter(c -> c.params() != null
                        && "hall".equals(String.valueOf(c.params().get("component_id"))))
                .count();
        assertEquals(2, halls, "ambiguous ownership must not silently rename");
        assertEquals("hall", roof.params().get("host_id"));
    }
}
