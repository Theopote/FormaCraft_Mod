package com.formacraft.common.typology;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypologyPatchBridgeTest {
    @org.junit.jupiter.api.BeforeAll
    static void initializeRegistries() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }


    @AfterEach
    void clearAnchor() {
        TypologyPatchBridge.clearPlanWorldAnchor();
    }

    @Test
    void worldBuildOriginCombinesPlanAnchorAndSlotOffset() {
        TypologyPatchBridge.setPlanWorldAnchor(new BlockPos(177, 67, -5));
        SemanticComponent semantic = new SemanticComponent(
                "STRUCTURE",
                new Slot("__global__", new Vec3i(2, 1, -3), null, "default", null, null),
                new Component("STRUCTURE", null, new Vec3i(0, 0, 0), null, List.of("typology:suspension_bridge"), null)
        );

        BlockPos worldOrigin = TypologyPatchBridge.worldBuildOrigin(semantic);

        assertEquals(new BlockPos(179, 68, -8), worldOrigin);
    }

    @Test
    void toBlockPatchesSubtractsWorldBuildOrigin() {
        BlockPos worldOrigin = new BlockPos(177, 67, -5);
        GeneratedStructure structure = new GeneratedStructure(
                null,
                worldOrigin,
                "test",
                List.of(new PlannedBlock(new BlockPos(200, 68, -5), Blocks.STONE.getDefaultState()))
        );

        List<BlockPatch> patches = TypologyPatchBridge.toBlockPatches(structure, worldOrigin);

        assertEquals(1, patches.size());
        assertEquals(23, patches.get(0).dx());
        assertEquals(1, patches.get(0).dy());
        assertEquals(0, patches.get(0).dz());
        assertEquals("minecraft:stone", patches.get(0).targetBlock());
    }
    @Test
    void preservesStairFacingHalfAndWaterloggedProperties() {
        var state = Blocks.OAK_STAIRS.getDefaultState()
                .with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, net.minecraft.util.math.Direction.WEST)
                .with(net.minecraft.state.property.Properties.BLOCK_HALF, net.minecraft.block.enums.BlockHalf.TOP)
                .with(net.minecraft.state.property.Properties.WATERLOGGED, true);
        BlockPos origin = new BlockPos(-10, 64, 20);
        var structure = new GeneratedStructure(null, origin, "test",
                List.of(new PlannedBlock(origin.add(-2, 3, 4), state)));
        var patch = TypologyPatchBridge.toBlockPatches(structure, origin).getFirst();
        assertEquals(-2, patch.dx());
        assertEquals(3, patch.dy());
        assertEquals(4, patch.dz());
        assertEquals("minecraft:oak_stairs[facing=west,half=top,shape=straight,waterlogged=true]",
                patch.targetBlock());
    }

    @Test
    void missingTargetStateIsRejectedInsteadOfBecomingStoneOrAir() {
        var structure = new GeneratedStructure(null, BlockPos.ORIGIN, "test",
                List.of(new PlannedBlock(BlockPos.ORIGIN, null)));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> TypologyPatchBridge.toBlockPatches(structure, BlockPos.ORIGIN));
    }

    @Test
    void plannedAirEmitsRemoveMatchingAssemblySemantics() {
        BlockPos origin = new BlockPos(10, 64, 20);
        GeneratedStructure structure = new GeneratedStructure(
                null,
                origin,
                "test",
                List.of(
                        new PlannedBlock(origin.add(1, 0, 0), Blocks.STONE.getDefaultState()),
                        new PlannedBlock(origin.add(2, 0, 0), Blocks.AIR.getDefaultState())
                )
        );
        List<BlockPatch> patches = TypologyPatchBridge.toBlockPatches(structure, origin);
        assertEquals(2, patches.size());
        BlockPatch solid = patches.stream().filter(p -> p.dx() == 1).findFirst().orElseThrow();
        BlockPatch air = patches.stream().filter(p -> p.dx() == 2).findFirst().orElseThrow();
        assertEquals(BlockPatch.PLACE, solid.action());
        assertEquals(BlockPatch.REMOVE, air.action());
        assertEquals("minecraft:air", air.targetBlock());
    }

    @Test
    void removeAirClearsPriorSolidViaPatchExecutor() {
        var access = new com.formacraft.test.FakeBlockMutationAccess();
        BlockPos origin = BlockPos.ORIGIN;
        // Builder last-write-wins: planned AIR at a cell that currently has stone in the world
        GeneratedStructure structure = new GeneratedStructure(null, origin, "test",
                List.of(new PlannedBlock(origin.add(0, 1, 0), Blocks.AIR.getDefaultState())));
        access.states.put(new BlockPos(0, 1, 0), Blocks.STONE.getDefaultState());
        List<BlockPatch> patches = TypologyPatchBridge.toBlockPatches(structure, origin);
        assertEquals(BlockPatch.REMOVE, patches.getFirst().action());
        var result = com.formacraft.common.patch.PatchExecutor.applyToAccess(access, origin, patches);
        assertEquals(1, result.applied());
        assertTrue(access.states.get(new BlockPos(0, 1, 0)).isAir());
    }
}
