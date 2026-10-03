package com.formacraft.server.generation.component.impl;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.patch.PatchExecutor;
import com.formacraft.test.*;
import net.minecraft.block.*;
import net.minecraft.block.enums.*;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AssemblyPatchStateTest {
    @BeforeAll static void initialize() { MinecraftRegistryTestBootstrap.initialize(); }
    @Test void stairsKeepFacingHalfShapeAndWaterloggedThroughPatchExecution() {
        var state = Blocks.OAK_STAIRS.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.WEST)
            .with(Properties.BLOCK_HALF, BlockHalf.TOP).with(Properties.STAIR_SHAPE, StairShape.INNER_LEFT)
            .with(Properties.WATERLOGGED, true);
        var local = new BlockPos(-3, 4, 2); var origin = new BlockPos(10, 2, -200);
        var patches = AssemblyPatchGenerator.toPatches(List.of(new PlannedBlock(local, state)));
        var access = new FakeBlockMutationAccess();
        assertEquals(1, PatchExecutor.applyToAccess(access, origin, patches).applied());
        assertEquals(state, access.states.get(origin.add(local)));
    }
    @Test void slabAndAxisPropertiesAlsoSurviveAssemblyBridge() {
        var slab = Blocks.SMOOTH_STONE_SLAB.getDefaultState().with(Properties.SLAB_TYPE, SlabType.TOP);
        var beam = Blocks.OAK_LOG.getDefaultState().with(Properties.AXIS, Direction.Axis.X);
        var a = new BlockPos(0, 4, 0); var b = new BlockPos(1, 5, 0);
        var access = new FakeBlockMutationAccess();
        assertEquals(2, PatchExecutor.applyToAccess(access, BlockPos.ORIGIN,
            AssemblyPatchGenerator.toPatches(List.of(new PlannedBlock(a, slab), new PlannedBlock(b, beam)))).applied());
        assertEquals(slab, access.states.get(a)); assertEquals(beam, access.states.get(b));
    }
    @Test void laterAirRemovesEarlierPlacementWithoutChangingPatchOrder() {
        var pos = new BlockPos(2, 3, -1); var access = new FakeBlockMutationAccess();
        var patches = AssemblyPatchGenerator.toPatches(List.of(new PlannedBlock(pos, Blocks.STONE.getDefaultState()),
            new PlannedBlock(pos, Blocks.AIR.getDefaultState())));
        assertEquals(2, PatchExecutor.applyToAccess(access, BlockPos.ORIGIN, patches).applied());
        assertTrue(access.states.get(pos).isAir());
    }
}
