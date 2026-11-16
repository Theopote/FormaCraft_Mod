package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.EntranceGenerator;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthoredEntranceCanopyAlignmentTest {
    private Component mass() {
        return new Component("MASS_MAIN", "main", new Vec3i(10, 0, 20), new Dimensions(12, 16, 12),
                List.of(), Map.of("component_id", "mass", "anchor_mode", "min_corner"));
    }

    @Test void authoredCanopyProjectsOutsideEachDoorWallAndCoversTheDoorBay() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
        for (var face : GlobalConstraints.Facing.values()) {
            var params = new HashMap<String, Object>();
            params.put("facing", face.name());
            params.put("door_width", 3);
            params.put("door_height", 3);
            params.put("canopy_depth", 2);
            var door = new Component("ENTRANCE", "main", new Vec3i(14, 1, 24), new Dimensions(5, 1, 4), List.of(), params);
            var aligned = ComponentSatelliteAligner.alignEntranceToMass(door, mass(), null, GlobalConstraints.Facing.SOUTH);
            assertEquals(2, aligned.params().get("canopy_depth"));
            boolean side = face == GlobalConstraints.Facing.EAST || face == GlobalConstraints.Facing.WEST;
            assertEquals(3, side ? aligned.dimensions().width() : aligned.dimensions().depth());
            int outer = switch (face) {
                case EAST -> 8;
                case WEST -> 23;
                case SOUTH -> 18;
                case NORTH -> 37;
            };
            var patches = new EntranceGenerator().generate(new SemanticComponent("ENTRANCE", null, aligned, "DEFAULT"));
            int center = side ? aligned.relativePosition().z() + aligned.dimensions().depth() / 2
                    : aligned.relativePosition().x() + aligned.dimensions().width() / 2;
            assertTrue(patches.stream().anyMatch(p -> BlockPatch.PLACE.equals(p.action()) && p.dy() == 4
                    && (side ? p.dx() == outer && p.dz() == center : p.dz() == outer && p.dx() == center)), face.name());
            assertEquals(new Vec3i(14, 1, 24), door.relativePosition());
        }
    }

    @Test void stairPortalDoesNotGrowACanopyAcrossItsLanding() {
        var door = new Component("ENTRANCE", "main", new Vec3i(14, 5, 24), new Dimensions(5, 1, 4), List.of(),
                Map.of("facing", "SOUTH", "stair_connection", "stairs", "canopy_depth", 2));
        var aligned = ComponentSatelliteAligner.alignEntranceToMass(door, mass(), null, GlobalConstraints.Facing.SOUTH);
        assertEquals(0, aligned.params().get("canopy_depth"));
        assertEquals(1, aligned.dimensions().depth());
        assertEquals(5, aligned.relativePosition().y());
    }
}
