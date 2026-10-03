package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.test.PatchTestSnapshot;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PostProcessGeometryPreservationTest {
    private PostProcessContext context() {
        return PostProcessContext.create(LlmPlanTestFixtures.builder().styleProfile("DEFAULT").build(), BlockPos.ORIGIN);
    }
    private BlockPatch block(int x, int y, int z, String state) { return new BlockPatch(BlockPatch.PLACE, x, y, z, state); }
    @Test void genericEnhancementPreservesStairsSlabsGlassAndDoors() {
        var stairs = "minecraft:cobblestone_stairs[facing=east,half=top]";
        var slab = "minecraft:stone_brick_slab[type=top]";
        var glass = "minecraft:glass";
        var door = "minecraft:oak_door[facing=south,half=lower]";
        var input = List.of(block(0, 0, 0, "minecraft:stone_bricks"), block(4, 7, 4, "minecraft:stone_bricks"),
            block(0, 3, 0, stairs), block(4, 3, 4, slab), block(0, 4, 4, glass), block(4, 4, 0, door));
        var result = PatchTestSnapshot.blocks(new DetailEnhancementPostProcessor().process(input, context()));
        assertEquals(stairs, result.get(new Vec3i(0, 3, 0)));
        assertEquals(slab, result.get(new Vec3i(4, 3, 4)));
        assertEquals(glass, result.get(new Vec3i(0, 4, 4)));
        assertEquals(door, result.get(new Vec3i(4, 4, 0)));
    }
    @Test void genericEnhancementDoesNotRefillRemovedCornerOrAirWindow() {
        var input = List.of(block(0, 0, 0, "minecraft:stone_bricks"), block(4, 7, 4, "minecraft:stone_bricks"),
            block(0, 4, 0, "minecraft:stone_bricks"), block(4, 4, 4, "minecraft:stone_bricks"),
            new BlockPatch(BlockPatch.REMOVE, 0, 4, 0, "minecraft:air"), block(4, 4, 4, "minecraft:air"));
        var result = PatchTestSnapshot.blocks(new DetailEnhancementPostProcessor().process(input, context()));
        assertFalse(result.containsKey(new Vec3i(0, 4, 0)));
        assertFalse(result.containsKey(new Vec3i(4, 4, 4)));
    }
    @Test void materialVariationNeverTurnsShapedStatesIntoFullBlocks() {
        var input = new ArrayList<BlockPatch>();
        var states = List.of("minecraft:cobblestone_stairs[facing=west,half=top,shape=straight,waterlogged=true]",
            "minecraft:cobblestone_slab[type=top,waterlogged=false]", "minecraft:cobblestone_wall[north=low,up=true]");
        for (int x = 0; x < 256; x++) for (int z = 0; z < states.size(); z++) input.add(block(x, 3, z, states.get(z)));
        var output = new MaterialVariationPostProcessor().process(input, context());
        for (var patch : output) assertEquals(states.get(patch.dz()), patch.targetBlock());
    }
}
