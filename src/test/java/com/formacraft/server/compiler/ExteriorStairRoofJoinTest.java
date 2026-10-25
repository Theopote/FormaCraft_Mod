package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExteriorStairRoofJoinTest {
    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void exteriorStairMayCarveLinkedRoofOnlyInsideEndpointEnvelope() {
        var roof = new Component("ROOF", "s", new Vec3i(0, 4, 0), new Dimensions(12, 12, 3), List.of(),
                Map.of("component_id", "roof", "host_id", "hall", "building_id", "hall"));
        var stair = new Component("STRUCTURE", "s", new Vec3i(0, 0, 0), new Dimensions(3, 8, 8),
                List.of("stair:straight_single_run"),
                Map.of("exterior", true, "host_id", "hall", "building_id", "hall", "width", 3, "clearHeight", 2,
                        "landing_length", 1,
                        "from", Map.of("x", 0, "y", 0, "z", 0),
                        "to", Map.of("x", 0, "y", 4, "z", 5)));
        var index = new PatchOccupancyIndex();
        var near = new BlockPos(0, 5, 5);
        var far = new BlockPos(10, 5, 10);
        index.commit(roof, List.of(stone(near), stone(far)));
        assertTrue(PatchOccupancyIndex.withinExteriorStairEnvelope(stair, near));
        assertFalse(PatchOccupancyIndex.withinExteriorStairEnvelope(stair, far));
        assertTrue(index.validate(stair, List.of(remove(near)), Set.of()).isEmpty());
        var farConflict = index.validate(stair, List.of(remove(far)), Set.of());
        assertTrue(farConflict.isPresent(), "non-connection roof cell must stay protected");
        assertEquals("unauthorized_remove", farConflict.get().reason());
    }

    @Test
    void exteriorWithoutHostLinkCannotCarveArbitraryRoofEvenInEnvelope() {
        var roof = new Component("ROOF", "s", new Vec3i(0, 4, 0), new Dimensions(8, 8, 2), List.of(),
                Map.of("component_id", "roof", "host_id", "other"));
        var stair = new Component("STRUCTURE", "s", new Vec3i(0, 0, 0), new Dimensions(3, 6, 6),
                List.of("stair:straight_single_run"),
                Map.of("exterior", true, "width", 2, "clearHeight", 2,
                        "from", Map.of("x", 0, "y", 0, "z", 0),
                        "to", Map.of("x", 0, "y", 4, "z", 4)));
        var index = new PatchOccupancyIndex();
        var pos = new BlockPos(0, 5, 4);
        index.commit(roof, List.of(stone(pos)));
        assertTrue(PatchOccupancyIndex.withinExteriorStairEnvelope(stair, pos));
        assertTrue(index.validate(stair, List.of(remove(pos)), Set.of()).isPresent());
    }

    private static BlockPatch stone(BlockPos p) {
        return new BlockPatch(BlockPatch.PLACE, p.getX(), p.getY(), p.getZ(), "minecraft:stone_bricks");
    }

    private static BlockPatch remove(BlockPos p) {
        return new BlockPatch(BlockPatch.REMOVE, p.getX(), p.getY(), p.getZ(), "minecraft:air");
    }
}