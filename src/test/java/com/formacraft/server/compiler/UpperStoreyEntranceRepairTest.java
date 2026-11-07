package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UpperStoreyEntranceRepairTest {

    @Test
    void dropsSecondStoreyStreetDoorWithoutDeck() {
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 8), List.of(),
                Map.of("component_id", "house", "anchor_mode", "min_corner",
                        "floor_count", 2, "floor_height", 4));
        Map<String, Object> upperDoor = new HashMap<>();
        upperDoor.put("host_id", "house");
        upperDoor.put("facing", "SOUTH");
        upperDoor.put("door_width", 2);
        upperDoor.put("anchor_mode", "min_corner");
        Component door = new Component(
                "ENTRANCE", "main", new Vec3i(4, 4, 0),
                new Dimensions(4, 1, 3), List.of(), upperDoor);
        List<Component> parts = new ArrayList<>(List.of(mass, door));
        UpperStoreyEntranceRepair.apply(parts);
        assertEquals(1, parts.size());
        assertEquals("MASS_MAIN", parts.getFirst().componentType());
    }

    @Test
    void keepsUpperDoorWhenTouchingBalcony() {
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 8), List.of(),
                Map.of("component_id", "house", "anchor_mode", "min_corner",
                        "floor_count", 2, "floor_height", 4));
        Map<String, Object> upperDoor = new HashMap<>();
        upperDoor.put("host_id", "house");
        upperDoor.put("facing", "SOUTH");
        upperDoor.put("door_width", 2);
        upperDoor.put("anchor_mode", "min_corner");
        Component door = new Component(
                "ENTRANCE", "main", new Vec3i(4, 4, 0),
                new Dimensions(4, 1, 3), List.of(), upperDoor);
        Component balcony = new Component(
                "BALCONY", "main", new Vec3i(3, 4, -2),
                new Dimensions(6, 3, 1), List.of(),
                Map.of("component_id", "balc", "host_id", "house", "anchor_mode", "min_corner"));
        List<Component> parts = new ArrayList<>(List.of(mass, door, balcony));
        UpperStoreyEntranceRepair.apply(parts);
        Component kept = parts.stream()
                .filter(c -> "ENTRANCE".equals(c.componentType()))
                .findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, kept.params().get("platform_door"));
    }

    @Test
    void keepsUserExplicitUpperDoorWithoutDeck() {
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 8), List.of(),
                Map.of("component_id", "house", "anchor_mode", "min_corner",
                        "floor_count", 2, "floor_height", 4));
        Map<String, Object> upperDoor = new HashMap<>();
        upperDoor.put("host_id", "house");
        upperDoor.put("facing", "SOUTH");
        upperDoor.put("door_width", 2);
        upperDoor.put("anchor_mode", "min_corner");
        upperDoor.put("constraint_source", "USER_EXPLICIT");
        upperDoor.put("door_explicit", true);
        Component door = new Component(
                "ENTRANCE", "main", new Vec3i(4, 4, 0),
                new Dimensions(4, 1, 3), List.of(), upperDoor);
        List<Component> parts = new ArrayList<>(List.of(mass, door));
        UpperStoreyEntranceRepair.apply(parts);
        assertEquals(2, parts.size());
        assertTrue(parts.stream().anyMatch(c -> "ENTRANCE".equals(c.componentType())));
    }

    @Test
    void ignoresForeignHostBalconyAsLanding() {
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 8), List.of(),
                Map.of("component_id", "house", "anchor_mode", "min_corner",
                        "floor_count", 2, "floor_height", 4));
        Map<String, Object> upperDoor = new HashMap<>();
        upperDoor.put("host_id", "house");
        upperDoor.put("facing", "SOUTH");
        upperDoor.put("door_width", 2);
        upperDoor.put("anchor_mode", "min_corner");
        Component door = new Component(
                "ENTRANCE", "main", new Vec3i(4, 4, 0),
                new Dimensions(4, 1, 3), List.of(), upperDoor);
        Component foreignBalcony = new Component(
                "BALCONY", "other", new Vec3i(3, 4, -2),
                new Dimensions(6, 3, 1), List.of(),
                Map.of("component_id", "balc", "host_id", "other_house", "anchor_mode", "min_corner"));
        List<Component> parts = new ArrayList<>(List.of(mass, door, foreignBalcony));
        UpperStoreyEntranceRepair.apply(parts);
        assertTrue(parts.stream().noneMatch(c -> "ENTRANCE".equals(c.componentType())),
                "foreign-host balcony must not rescue floating door");
    }

    @Test
    void keepsGroundEntrance() {
        Component mass = new Component(
                "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 8), List.of(),
                Map.of("component_id", "house", "anchor_mode", "min_corner",
                        "floor_count", 2, "floor_height", 4));
        Map<String, Object> doorParams = new HashMap<>();
        doorParams.put("host_id", "house");
        doorParams.put("facing", "SOUTH");
        doorParams.put("door_width", 3);
        doorParams.put("anchor_mode", "min_corner");
        Component door = new Component(
                "ENTRANCE", "main", new Vec3i(4, 0, 0),
                new Dimensions(4, 1, 4), List.of(), doorParams);
        List<Component> parts = new ArrayList<>(List.of(mass, door));
        UpperStoreyEntranceRepair.apply(parts);
        assertEquals(2, parts.size());
        assertTrue(parts.stream().anyMatch(c -> "ENTRANCE".equals(c.componentType())));
    }
}
