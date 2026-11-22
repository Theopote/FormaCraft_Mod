package com.formacraft.server.assembly;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PUSH_ORIGIN offsets must rotate with entranceFacing — matching put() local coords —
 * for plain ASSEMBLY ops (no {@code _fbd_node}), not only the FBD path.
 */
class MetaAssemblyPushOriginFacingTest {
    @BeforeAll
    static void initialize() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @ParameterizedTest
    @EnumSource(value = Direction.class, names = {"NORTH", "SOUTH", "EAST", "WEST"})
    void plainAssemblyPushOriginRotatesWithEntranceFacing(Direction facing) {
        BlockPos worldOrigin = new BlockPos(100, 64, -30);
        var push = new HashMap<String, Object>();
        push.put("op", "PUSH_ORIGIN");
        push.put("dx", 10);
        push.put("dy", 0);
        push.put("dz", 0);
        // Single local cell at the pushed origin, plus one cell at local +X to prove axes match.
        var atFrame = clearCell(0, 0, 0);
        var atLocalX = clearCell(1, 0, 0);
        var blocks = new MetaAssemblyEngine().executeGeometry(
                AssemblySpec.of(null, null, List.of(push, atFrame, atLocalX)),
                new MetaAssemblyEngine.Context(null, worldOrigin, facing, null));

        BlockPos frame = worldOrigin.add(PlacementUtil.local(BlockPos.ORIGIN, facing, 10, 0, 0));
        BlockPos localX = PlacementUtil.local(frame, facing, 1, 0, 0);
        Set<BlockPos> positions = blocks.stream().map(PlannedBlock::getPos).collect(Collectors.toSet());
        assertEquals(2, positions.size());
        assertTrue(positions.contains(frame), "frame origin for " + facing);
        assertTrue(positions.contains(localX), "local +X for " + facing);

        // Non-SOUTH must not keep the old world-axis +X frame translation.
        if (facing != Direction.SOUTH) {
            assertFalse(positions.contains(worldOrigin.add(10, 0, 0)),
                    "unrotated +X frame must not appear for facing=" + facing);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Direction.class, names = {"NORTH", "EAST", "WEST"})
    void plainAssemblyNestedPushOriginComposesRotatedOffsets(Direction facing) {
        BlockPos worldOrigin = BlockPos.ORIGIN;
        var pushA = new HashMap<String, Object>(Map.of("op", "PUSH_ORIGIN", "dx", 8, "dy", 0, "dz", 0));
        var pushB = new HashMap<String, Object>(Map.of("op", "PUSH_ORIGIN", "dx", 0, "dy", 0, "dz", 4));
        var blocks = new MetaAssemblyEngine().executeGeometry(
                AssemblySpec.of(null, null, List.of(pushA, pushB, clearCell(0, 0, 0))),
                new MetaAssemblyEngine.Context(null, worldOrigin, facing, null));
        assertEquals(1, blocks.size());
        BlockPos frameA = worldOrigin.add(PlacementUtil.local(BlockPos.ORIGIN, facing, 8, 0, 0));
        BlockPos frameB = frameA.add(PlacementUtil.local(BlockPos.ORIGIN, facing, 0, 0, 4));
        assertEquals(frameB, blocks.getFirst().getPos());
        assertEquals(Blocks.AIR.getDefaultState(), blocks.getFirst().getTargetState());
        assertNotEquals(worldOrigin.add(8, 0, 4), frameB);
    }

    private static Map<String, Object> clearCell(int x, int y, int z) {
        var op = new HashMap<String, Object>();
        op.put("op", "CLEAR_BOX");
        op.put("x0", x);
        op.put("y0", y);
        op.put("z0", z);
        op.put("x1", x);
        op.put("y1", y);
        op.put("z1", z);
        return op;
    }
}
