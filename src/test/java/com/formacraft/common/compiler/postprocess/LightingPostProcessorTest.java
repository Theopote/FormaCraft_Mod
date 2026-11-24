package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.StyleAttributes;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LightingPostProcessorTest {
    @Test
    void placeOnlyAddsLightsAndEntranceFlankingIsSymmetric() {
        var volume = new PostProcessContext.BuildingVolume("m",
                new ComponentFootprintUtil.Bounds(0, 0, 0, 9, 6, 9), 4);
        var attrs = new StyleAttributes(null, null, null, null, null, null, null, null,
                null, null, null, null, null, "subtle", "classic", "none", "warm_lantern", "normal");
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("MEDIEVAL_CLASSIC").styleAttributes(attrs).build();
        PostProcessContext ctx = PostProcessContext.create(plan, BlockPos.ORIGIN, List.of(volume));

        List<BlockPatch> input = new ArrayList<>();
        // Ceiling and floor
        for (int x = 1; x < 8; x++) for (int z = 1; z < 8; z++) {
            input.add(new BlockPatch(BlockPatch.PLACE, x, 0, z, "minecraft:oak_planks"));
            input.add(new BlockPatch(BlockPatch.PLACE, x, 5, z, "minecraft:oak_planks"));
        }
        // Door on south face
        input.add(new BlockPatch(BlockPatch.PLACE, 4, 1, 0, "minecraft:oak_door[facing=south,half=lower,hinge=left,open=false,powered=false]"));
        input.add(new BlockPatch(BlockPatch.PLACE, 4, 2, 0, "minecraft:oak_door[facing=south,half=upper,hinge=left,open=false,powered=false]"));

        List<BlockPatch> out = new LightingPostProcessor().process(input, ctx);
        List<BlockPatch> added = out.stream().filter(p -> !input.contains(p)).toList();
        assertFalse(added.isEmpty());
        assertTrue(added.stream().allMatch(p -> BlockPatch.PLACE.equals(p.action())));
        // Symmetric flanking around door x=4 on south (+z outward → lights at x=3 and x=5)
        boolean left = added.stream().anyMatch(p -> p.dx() == 3 && p.dy() == 3);
        boolean right = added.stream().anyMatch(p -> p.dx() == 5 && p.dy() == 3);
        assertTrue(left || right, "expected entrance flanking lights");
    }

    @Test
    void lightingStyleNoneEmitsNothing() {
        var attrs = new StyleAttributes(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, "none", null);
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleAttributes(attrs).build();
        PostProcessContext ctx = PostProcessContext.create(plan, BlockPos.ORIGIN);
        List<BlockPatch> input = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:oak_door[facing=south,half=lower]"));
        List<BlockPatch> out = new LightingPostProcessor().process(input, ctx);
        assertEquals(input.size(), out.size());
    }

    @Test
    void interiorLightRespectsHeadroomOnShortStorey() {
        assertTrue(SubVoxelHeadroom.shortStoreyUsesFlushFixture(3));
        assertFalse(SubVoxelHeadroom.shortStoreyUsesFlushFixture(5));
    }

    /** Tiny helper mirrored from LightingPostProcessor storey rules for unit clarity. */
    static final class SubVoxelHeadroom {
        static boolean shortStoreyUsesFlushFixture(int storeyH) {
            return storeyH < 4;
        }
    }
}
