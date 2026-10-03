package com.formacraft.server.network;

import com.formacraft.common.patch.*;
import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.test.*;
import net.minecraft.block.*;
import net.minecraft.block.enums.*;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanPatchConverterTest {
    @BeforeAll static void initialize() { MinecraftRegistryTestBootstrap.initialize(); }
    @Test void previewAndExecutorAgreeOnCompleteStairStateAndOrigin() {
        var state = Blocks.OAK_STAIRS.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.WEST)
            .with(Properties.BLOCK_HALF, BlockHalf.TOP).with(Properties.STAIR_SHAPE, StairShape.INNER_LEFT).with(Properties.WATERLOGGED, true);
        var patches = List.of(new BlockPatch("replace", -3, 4, 2, BlockStateStringUtil.fromState(state)));
        var origin = new BlockPos(10, 2, -20);
        var converted = PlanPatchConverter.convert(patches, origin);
        assertEquals(0, converted.invalid()); assertEquals(origin.add(-3, 4, 2), converted.blocks().getFirst().getPos());
        assertEquals(state, converted.blocks().getFirst().getTargetState());
        var access = new FakeBlockMutationAccess();
        assertEquals(1, PatchExecutor.applyToAccess(access, origin, patches).applied());
        assertEquals(converted.blocks().getFirst().getTargetState(), access.states.get(origin.add(-3, 4, 2)));
    }
    @Test void removeIgnoresTargetAndOrderIsPreserved() {
        var patches = List.of(new BlockPatch("place", 1, 2, 3, "minecraft:stone"),
            new BlockPatch("remove", 1, 2, 3, null), new BlockPatch(" remove ", 2, 2, 3, "minecraft:oak_stairs"));
        var result = PlanPatchConverter.convert(patches, BlockPos.ORIGIN);
        assertEquals(0, result.invalid()); assertEquals(3, result.blocks().size());
        assertEquals(Blocks.STONE.getDefaultState(), result.blocks().getFirst().getTargetState());
        assertTrue(result.blocks().get(1).getTargetState().isAir()); assertTrue(result.blocks().get(2).getTargetState().isAir());
    }
    @Test void unknownBlocksActionsAndMalformedPropertiesAreCountedAndRejected() {
        var patches = new ArrayList<BlockPatch>(); patches.add(null);
        for (String id : List.of("minecraft:no_such_block", "minecraft:oak_stairs[facing=up]", "minecraft:stone[no_such_property=true]",
                "minecraft:oak_stairs[facing=east,facing=west]", "minecraft:oak_stairs[facing=east"))
            patches.add(new BlockPatch("place", 0, 1, 0, id));
        patches.add(new BlockPatch("explode", 0, 1, 0, "minecraft:stone"));
        var result = PlanPatchConverter.convert(patches, BlockPos.ORIGIN);
        assertEquals(patches.size(), result.invalid()); assertTrue(result.blocks().isEmpty());
    }
}
