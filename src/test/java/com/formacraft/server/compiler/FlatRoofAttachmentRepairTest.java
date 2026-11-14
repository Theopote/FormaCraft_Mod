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

    @Test
    void prefersOverlappingAtticOverTallNeighborWithoutCoverage() {
        Component main = new Component(
                "MASS_MAIN", "body", new Vec3i(0, 0, 0),
                new Dimensions(10, 10, 8), List.of(),
                Map.of("component_id", "house", "anchor_mode", "min_corner"));
        Component attic = new Component(
                "MASS_SECONDARY", "body", new Vec3i(1, 8, 1),
                new Dimensions(8, 8, 3), List.of(),
                Map.of("component_id", "house_attic", "host_id", "house",
                        "role", "attic", "anchor_mode", "min_corner"));
        // Tall neighbor — higher top but no XZ coverage of the roof.
        Component neighbor = new Component(
                "MASS_SECONDARY", "body", new Vec3i(40, 0, 40),
                new Dimensions(6, 6, 20), List.of(),
                Map.of("component_id", "tower_storey", "role", "attic",
                        "anchor_mode", "min_corner"));
        Component roof = new Component(
                "ROOF", "body", new Vec3i(1, 12, 1),
                new Dimensions(8, 8, 1), List.of(),
                Map.of("component_id", "house_roof", "host_id", "house",
                        "roof_type", "flat", "anchor_mode", "min_corner"));
        LlmPlan repaired = FlatRoofAttachmentRepair.apply(LlmPlanTestFixtures.builder()
                .components(List.of(main, attic, neighbor, roof))
                .build());
        Component snapped = repaired.components().stream()
                .filter(c -> "house_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals("house_attic", snapped.params().get("host_id"),
                "must rebind to covering attic, not a tall distant storey");
        assertEquals("house_attic", snapped.params().get("host_part_id"));
        // attic top enclosed = 8+3-1 = 10
        assertEquals(10, snapped.relativePosition().y());
    }

    @Test
    void crossSlotUpperStoreyDoesNotPreventRoofSnap() {
        // Host slot Y=10, roof slot Y=20 → expected local = hostTop(7)+10-20 = -3.
        // A hosted annex at plan Y=10 sits above the *wrong* bodyTopPlan (=expected+hostSlotY=7)
        // but below the correct host top plane (17) — must not suppress snap.
        Component body = new Component(
                "MASS_MAIN", "body", new Vec3i(0, 0, 0),
                new Dimensions(6, 6, 8), List.of(),
                Map.of("component_id", "house", "anchor_mode", "min_corner"));
        // Same XZ as the roof; height 11 → plan top = 10+11-1 = 20 (>= roofPlanY) so preferAtticHost
        // does not rebind, while plan base Y=10 still sits above the buggy threshold (7) and below
        // the correct host top plane (17).
        Component annex = new Component(
                "MASS_SECONDARY", "body", new Vec3i(0, 0, 0),
                new Dimensions(6, 6, 11), List.of(),
                Map.of("component_id", "house_annex", "host_id", "house",
                        "anchor_mode", "min_corner"));
        Component roof = new Component(
                "ROOF", "roof", new Vec3i(0, 0, 0),
                new Dimensions(6, 6, 1), List.of(),
                Map.of("component_id", "house_roof", "host_id", "house",
                        "roof_type", "flat", "anchor_mode", "min_corner"));
        Slot bodySlot = new Slot("body", new Vec3i(0, 10, 0), null, null, null, null);
        Slot roofSlot = new Slot("roof", new Vec3i(0, 20, 0), null, null, null, null);
        LlmPlan repaired = FlatRoofAttachmentRepair.apply(LlmPlanTestFixtures.builder()
                .layout(new Layout(null, false, List.of(bodySlot, roofSlot)))
                .components(List.of(body, annex, roof))
                .build());
        Component snapped = repaired.components().stream()
                .filter(c -> "house_roof".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals(-3, snapped.relativePosition().y(),
                "cross-slot annex below host top must not block flat-roof snap");
    }
}
