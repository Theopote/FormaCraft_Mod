package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FlatRoofAttachmentRepairTest {

    @Test
    void crossSlotYOffsetSnapsLocalRoofY() {
        // Host slot Y=10, roof slot Y=11 → local roof Y must compensate by -1.
        Component body = new Component(
                "MASS_MAIN", "body", new Vec3i(0, 0, 0),
                new Dimensions(6, 6, 8), List.of(),
                Map.of("component_id", "house", "anchor_mode", "min_corner"));
        Component roof = new Component(
                "ROOF", "roof", new Vec3i(0, 7, 0),
                new Dimensions(6, 6, 1), List.of(),
                Map.of("component_id", "house_roof", "host_id", "house",
                        "roof_type", "flat", "anchor_mode", "min_corner"));
        Slot bodySlot = new Slot("body", new Vec3i(20, 10, 30), null, null, null, null);
        Slot roofSlot = new Slot("roof", new Vec3i(20, 11, 30), null, null, null, null);
        LlmPlan repaired = FlatRoofAttachmentRepair.apply(LlmPlanTestFixtures.builder()
                .layout(new Layout(null, false, List.of(bodySlot, roofSlot)))
                .components(List.of(body, roof))
                .build());
        Component snapped = repaired.components().stream()
                .filter(c -> "house_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        // enclosed: hostTop=7, expectedLocal = 7 + 10 - 11 = 6
        assertEquals(6, snapped.relativePosition().y());
    }

    @Test
    void secondaryMassFlatRoofIsSnapped() {
        Component body = new Component(
                "MASS_SECONDARY", "side", new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 6), List.of(),
                Map.of("component_id", "side_hall", "anchor_mode", "min_corner"));
        Component roof = new Component(
                "ROOF", "side", new Vec3i(0, 3, 0),
                new Dimensions(8, 8, 1), List.of(),
                Map.of("component_id", "side_hall_roof", "host_id", "side_hall",
                        "roof_type", "flat", "anchor_mode", "min_corner"));
        LlmPlan repaired = FlatRoofAttachmentRepair.apply(LlmPlanTestFixtures.builder()
                .components(List.of(body, roof))
                .build());
        Component snapped = repaired.components().stream()
                .filter(c -> "side_hall_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals(5, snapped.relativePosition().y(), "enclosed MASS_SECONDARY top is maxY-1");
    }
}
