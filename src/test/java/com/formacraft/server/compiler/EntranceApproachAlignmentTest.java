package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.RoadGenerator;
import com.formacraft.common.llm.dto.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EntranceApproachAlignmentTest {
    private Component mass() {
        return new Component("MASS_MAIN", "main", new Vec3i(10, 0, 20), new Dimensions(12, 16, 12),
                List.of(), Map.of("component_id", "mass", "anchor_mode", "min_corner"));
    }
    private Component paving(String host, String slot) {
        return new Component("PAVING", slot, new Vec3i(100, 5, 100), new Dimensions(6, 8, 2),
                List.of("entry_plaza"), host == null ? Map.of("component_id", "plaza")
                : Map.of("component_id", "plaza", "host_id", host));
    }
    private void align(List<Component> components, Set<String> assemblySlots) {
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true).mode(LlmPlan.Mode.build).styleProfile("DEFAULT").components(components).build();
        ComponentSatelliteAligner.realignSatellitesToMass(components, plan, Map.of(), assemblySlots);
    }

    @Test void followsFinalDoorInEveryDirectionAndInputOrderWithOrWithoutCanopy() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
        for (var face : GlobalConstraints.Facing.values()) for (boolean pavingFirst : List.of(false, true))
                for (int canopy : List.of(0, 2)) {
            var door = new Component("ENTRANCE", "main", new Vec3i(14, 1, 24), new Dimensions(5, 1, 4), List.of(),
                    Map.of("component_id", "door", "host_id", "mass", "facing", face.name(),
                            "canopy_depth", canopy, "door_width", 3, "door_height", 3));
            var original = paving("door", "main");
            var components = new ArrayList<>(pavingFirst ? List.of(mass(), original, door) : List.of(mass(), door, original));
            var ids = components.stream().map(c -> c.params().get("component_id")).toList();
            align(components, Set.of());
            var pad = components.stream().filter(c -> "PAVING".equals(c.componentType())).findFirst().orElseThrow();
            var finalDoor = components.stream().filter(c -> "ENTRANCE".equals(c.componentType())).findFirst().orElseThrow();
            var p = pad.relativePosition();
            switch (face) {
                case SOUTH -> assertEquals(20, p.z() + pad.dimensions().depth());
                case NORTH -> assertEquals(36, p.z());
                case EAST -> assertEquals(10, p.x() + pad.dimensions().width());
                case WEST -> assertEquals(22, p.x());
            }
            boolean side = face == GlobalConstraints.Facing.EAST || face == GlobalConstraints.Facing.WEST;
            int center = side ? finalDoor.relativePosition().z() + finalDoor.dimensions().depth() / 2
                    : finalDoor.relativePosition().x() + finalDoor.dimensions().width() / 2;
            assertEquals(center, side ? p.z() + pad.dimensions().depth() / 2 : p.x() + pad.dimensions().width() / 2);
            int top = finalDoor.relativePosition().y() - 1;
            var patches = new RoadGenerator().generate(new SemanticComponent("PAVING", null, pad, "DEFAULT"));
            assertEquals(top, patches.stream().mapToInt(c -> c.dy()).max().orElseThrow());
            assertEquals(original.dimensions(), pad.dimensions());
            assertEquals(original.features(), pad.features());
            assertEquals(ids, components.stream().map(c -> c.params().get("component_id")).toList());
        }
    }

    @Test void unrelatedUnboundAndOtherSlotPavingKeepTheirAuthoredPosition() {
        var door = new Component("ENTRANCE", "main", new Vec3i(14, 1, 20), new Dimensions(5, 1, 4), List.of(),
                Map.of("component_id", "door", "host_id", "mass", "facing", "SOUTH"));
        var plain = paving("mass", "main");
        var courtyard = new Component(plain.componentType(), plain.slotId(), plain.relativePosition(), plain.dimensions(),
                List.of("courtyard"), plain.params());
        for (var original : List.of(paving(null, "main"), courtyard, paving("missing", "main"), paving("door", "other"))) {
            var components = new ArrayList<>(List.of(mass(), door, original));
            align(components, Set.of());
            assertEquals(original.relativePosition(), components.get(2).relativePosition());
            align(components, Set.of());
            assertEquals(original.relativePosition(), components.get(2).relativePosition());
        }
        var original = paving("door", "main");
        var assembly = new ArrayList<>(List.of(mass(), door, original));
        align(assembly, Set.of("main"));
        assertSame(original, assembly.get(2));
    }

    private Component door(String id, String host, String facing) {
        return new Component("ENTRANCE", "main", new Vec3i(14, 1, 24), new Dimensions(5, 1, 4), List.of(),
                Map.of("component_id", id, "host_id", host, "facing", facing, "door_width", 3, "door_height", 3));
    }

    @Test void markedBodyOwnedPlazaConnectsToItsOnlyEntranceAndKeepsBodyOwnership() {
        for (boolean paramRole : List.of(false, true)) {
            var source = paving("mass", "main");
            var params = new HashMap<String, Object>(source.params());
            if (paramRole) params.put("role", "entrance_plaza");
            var plaza = new Component(source.componentType(), source.slotId(), source.relativePosition(), source.dimensions(),
                    paramRole ? List.of() : source.features(), params);
            var components = new ArrayList<>(List.of(mass(), plaza, door("front", "mass", "SOUTH")));
            align(components, Set.of());
            var result = components.get(1);
            assertEquals(20, result.relativePosition().z() + result.dimensions().depth());
            assertEquals("mass", result.params().get("host_id"));
            assertEquals("front", result.params().get("entrance_id"));
            assertEquals(source.dimensions(), result.dimensions());
            align(components, Set.of());
            assertEquals(result.relativePosition(), components.get(1).relativePosition());
            assertEquals(new Vec3i(100, 5, 100), source.relativePosition());
        }
    }

    @Test void multipleEntrancesRequireExplicitSelectionAndCannotUseAForeignBodyDoor() {
        var original = paving("mass", "main");
        var front = door("front", "mass", "SOUTH");
        var back = door("back", "mass", "NORTH");
        var ambiguous = new ArrayList<>(List.of(mass(), original, front, back));
        align(ambiguous, Set.of());
        assertEquals(original.relativePosition(), ambiguous.get(1).relativePosition());
        for (String selected : List.of("back", "foreign", "missing")) {
            var params = new HashMap<String, Object>(original.params());
            params.put("entrance_id", selected);
            var plaza = new Component(original.componentType(), original.slotId(), original.relativePosition(),
                    original.dimensions(), original.features(), params);
            var components = new ArrayList<>(List.of(mass(), plaza, front, back, door("foreign", "other_body", "EAST")));
            align(components, Set.of());
            if (selected.equals("back")) {
                assertEquals(36, components.get(1).relativePosition().z());
                assertEquals("mass", components.get(1).params().get("host_id"));
            } else assertEquals(original.relativePosition(), components.get(1).relativePosition());
        }
    }

    @Test void standaloneEntranceUsesItsGeneratorsDefaultCornerFrame() {
        var door = new Component("ENTRANCE", "main", new Vec3i(4, 1, 5), new Dimensions(5, 1, 4), List.of(),
                Map.of("component_id", "door", "facing", "SOUTH"));
        var components = new ArrayList<>(List.of(door, paving("door", "main")));
        align(components, Set.of());
        assertEquals(new Vec3i(3, -1, -3), components.get(1).relativePosition());
    }
}
