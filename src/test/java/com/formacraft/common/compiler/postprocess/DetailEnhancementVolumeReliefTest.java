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

class DetailEnhancementVolumeReliefTest {
    @Test
    void lShapeVolumesGetPerVolumePlinthNotGlobalAabbCorners() {
        // Two volumes forming an L — global AABB corner at (0,0)/(9,9) empty wing
        var v1 = new PostProcessContext.BuildingVolume("a",
                new ComponentFootprintUtil.Bounds(0, 0, 0, 4, 6, 4), 3);
        var v2 = new PostProcessContext.BuildingVolume("b",
                new ComponentFootprintUtil.Bounds(0, 0, 4, 10, 6, 8), 3);
        var attrs = new StyleAttributes(null, null, null, null, null, null, null, null,
                null, null, null, null, null, "deep", "classic", "subtle", "warm_lantern", "normal");
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleProfile("MEDIEVAL_CLASSIC")
                .styleAttributes(attrs)
                .build();
        PostProcessContext ctx = PostProcessContext.create(plan, BlockPos.ORIGIN, List.of(v1, v2));

        List<BlockPatch> input = new ArrayList<>();
        // Fill both volumes with wall shells
        for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++)
            for (int y = 0; y < 6; y++)
                if (x == 0 || x == 3 || z == 0 || z == 3)
                    input.add(new BlockPatch(BlockPatch.PLACE, x, y, z, "minecraft:stone_bricks"));
        for (int x = 0; x < 10; x++) for (int z = 4; z < 8; z++)
            for (int y = 0; y < 6; y++)
                if (x == 0 || x == 9 || z == 4 || z == 7)
                    input.add(new BlockPatch(BlockPatch.PLACE, x, y, z, "minecraft:stone_bricks"));

        List<BlockPatch> out = new DetailEnhancementPostProcessor().process(input, ctx);
        assertTrue(out.size() > input.size(), "expected outward relief patches");
        // Far empty corner of global AABB (9,0) should not receive a spurious pilaster from volume a
        assertTrue(out.stream().noneMatch(p -> p.dx() == 10 && p.dz() == -1 && p.dy() == 0
                && p.targetBlock() != null && p.targetBlock().contains("stairs")),
                "must not decorate far global AABB corner");
        // Plinth adjacent to volume a south face is expected
        assertTrue(out.stream().anyMatch(p -> p.dz() == -1 && p.dy() == 0 && BlockPatch.PLACE.equals(p.action())));
        // Both E/W and N/S pilaster outsides for a corner (not E/W-only)
        boolean eastPilaster = out.stream().anyMatch(p -> p.dx() == 4 && p.dz() == 0 && p.dy() > 0
                && BlockPatch.PLACE.equals(p.action()));
        boolean northPilaster = out.stream().anyMatch(p -> p.dx() == 3 && p.dz() == -1 && p.dy() > 0
                && BlockPatch.PLACE.equals(p.action()));
        assertTrue(eastPilaster && northPilaster, "corner pilasters must extrude on both adjacent faces");
        // Pilasters emit before plinth, so stair bases survive at minY
        assertTrue(out.stream().anyMatch(p -> p.dx() == 4 && p.dz() == 0 && p.dy() == 0
                        && p.targetBlock() != null && p.targetBlock().contains("stairs")),
                "pilaster stair base must not be swallowed by plinth slabs");
    }

    @Test
    void secondVolumeStillGetsReliefAfterFirstVolumeBudget() {
        // First volume huge enough to consume a large budget; second must still receive plinth
        var v1 = new PostProcessContext.BuildingVolume("big",
                new ComponentFootprintUtil.Bounds(0, 0, 0, 40, 8, 40), 4);
        var v2 = new PostProcessContext.BuildingVolume("wing",
                new ComponentFootprintUtil.Bounds(50, 0, 0, 56, 6, 6), 3);
        var attrs = new StyleAttributes(null, null, null, null, null, null, null, null,
                null, null, null, null, null, "subtle", "classic", "subtle", null, null);
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleAttributes(attrs).build();
        PostProcessContext ctx = PostProcessContext.create(plan, BlockPos.ORIGIN, List.of(v1, v2));
        List<BlockPatch> input = new ArrayList<>();
        for (int x = 0; x < 40; x++) for (int z = 0; z < 40; z++)
            if (x == 0 || x == 39 || z == 0 || z == 39)
                for (int y = 0; y < 8; y++)
                    input.add(new BlockPatch(BlockPatch.PLACE, x, y, z, "minecraft:stone_bricks"));
        for (int x = 50; x < 56; x++) for (int z = 0; z < 6; z++)
            if (x == 50 || x == 55 || z == 0 || z == 5)
                for (int y = 0; y < 6; y++)
                    input.add(new BlockPatch(BlockPatch.PLACE, x, y, z, "minecraft:stone_bricks"));
        List<BlockPatch> out = new DetailEnhancementPostProcessor().process(input, ctx);
        assertTrue(out.stream().anyMatch(p -> p.dx() >= 50 && p.dx() <= 56 && p.dz() == -1 && p.dy() == 0),
                "second volume must still receive plinth after first volume uses its own budget");
    }

    @Test
    void corniceEmitsWhenRoofSitsAboveWallNotAboveCorniceCell() {
        var volume = new PostProcessContext.BuildingVolume("m",
                new ComponentFootprintUtil.Bounds(0, 0, 0, 6, 5, 6), 4);
        var attrs = new StyleAttributes(null, null, null, null, null, null, null, null,
                null, null, null, null, null, "deep", "classic", "subtle", null, null);
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .styleAttributes(attrs).build();
        PostProcessContext ctx = PostProcessContext.create(plan, BlockPos.ORIGIN, List.of(volume));
        List<BlockPatch> input = new ArrayList<>();
        for (int x = 0; x < 6; x++) for (int z = 0; z < 6; z++) {
            for (int y = 0; y < 5; y++) {
                if (x == 0 || x == 5 || z == 0 || z == 5)
                    input.add(new BlockPatch(BlockPatch.PLACE, x, y, z, "minecraft:stone_bricks"));
            }
            // Roof only above the wall footprint (maxY+1 on wall cells), not over cornice extrusion
            if (x == 0 || x == 5 || z == 0 || z == 5)
                input.add(new BlockPatch(BlockPatch.PLACE, x, 5, z, "minecraft:oak_planks"));
        }
        List<BlockPatch> out = new DetailEnhancementPostProcessor().process(input, ctx);
        assertTrue(out.stream().anyMatch(p -> p.dz() == -1 && p.dy() == 4
                        && p.targetBlock() != null && p.targetBlock().contains("stairs")),
                "cornice brackets must still emit when roof covers the wall top, not the cornice cell");
    }
}
