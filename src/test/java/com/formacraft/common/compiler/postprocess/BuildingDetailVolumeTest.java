package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.test.PatchTestSnapshot;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildingDetailVolumeTest {
    private final DetailRulePostProcessor processor = new DetailRulePostProcessor();
    private LlmPlan plan(Map<String, Object> hints) {
        return LlmPlanTestFixtures.builder().styleProfile("DEFAULT").proportionHints(hints).build();
    }
    private BlockPatch wall(int x, int y, int z) { return new BlockPatch(BlockPatch.PLACE, x, y, z, "minecraft:stone_bricks"); }
    private PostProcessContext.BuildingVolume volume(String slot, int x, int y, int z, int h, int floor) {
        return new PostProcessContext.BuildingVolume(slot, new ComponentFootprintUtil.Bounds(x, y, z, x + 5, y + h, z + 5), floor);
    }
    @Test void foundationAndRoofDoNotMoveMassFloorBasisOrPerimeter() {
        var plan = plan(Map.of("floor_cornice", true));
        var input = List.of(wall(-2, 0, -2), wall(6, 0, 6), wall(0, 13, 0), wall(0, 12, 0),
            wall(4, 21, 4), wall(0, 30, 0), wall(2, 13, 2));
        var context = PostProcessContext.create(plan, BlockPos.ORIGIN, List.of(volume("main", 0, 10, 0, 12, 4)));
        var result = PatchTestSnapshot.blocks(processor.process(input, context));
        assertTrue(result.get(new Vec3i(0, 13, 0)).contains("half=top"));
        assertTrue(result.get(new Vec3i(4, 21, 4)).contains("half=top"));
        for (var pos : List.of(new Vec3i(-2, 0, -2), new Vec3i(0, 12, 0), new Vec3i(0, 30, 0), new Vec3i(2, 13, 2)))
            assertEquals("minecraft:stone_bricks", result.get(pos));
    }
    @Test void eachMassUsesItsOwnVerticalAndHorizontalBoundsAndFloorHeight() {
        var plan = plan(Map.of("floor_cornice", true));
        var context = PostProcessContext.create(plan, BlockPos.ORIGIN,
            List.of(volume("a", 0, 10, 0, 8, 4), volume("b", 20, -5, 30, 9, 3)));
        var result = PatchTestSnapshot.blocks(processor.process(List.of(wall(0, 13, 0), wall(20, -3, 30),
            wall(20, -2, 30), wall(10, 13, 10)), context));
        assertTrue(result.get(new Vec3i(0, 13, 0)).contains("half=top"));
        assertTrue(result.get(new Vec3i(20, -3, 30)).contains("half=top"));
        assertEquals("minecraft:stone_bricks", result.get(new Vec3i(20, -2, 30)));
        assertEquals("minecraft:stone_bricks", result.get(new Vec3i(10, 13, 10)));
    }
    @Test void roofEaveRuleUsesMassTopInsteadOfHigherRoofApex() {
        var plan = plan(Map.of("detail_rules", List.of(Map.of("when", Map.of("region", "perimeter", "y", "roof_eave", "block", "wall"),
            "action", Map.of("replace_with", "slab", "part", "WALL_ACCENT")))));
        var context = PostProcessContext.create(plan, BlockPos.ORIGIN, List.of(volume("main", 0, 10, 0, 8, 4)));
        var result = PatchTestSnapshot.blocks(processor.process(List.of(wall(0, 17, 0), wall(0, 23, 0)), context));
        assertTrue(result.get(new Vec3i(0, 17, 0)).contains("slab"));
        assertEquals("minecraft:stone_bricks", result.get(new Vec3i(0, 23, 0)));
    }
    @Test void overlappingMassesDoNotGuessOwnership() {
        var plan = plan(Map.of("floor_cornice", true));
        var context = PostProcessContext.create(plan, BlockPos.ORIGIN,
            List.of(volume("a", 0, 0, 0, 8, 4), volume("b", 0, 0, 0, 8, 3)));
        var result = PatchTestSnapshot.blocks(processor.process(List.of(wall(0, 3, 0)), context));
        assertEquals("minecraft:stone_bricks", result.get(new Vec3i(0, 3, 0)));
    }
    @Test void removedOutlierDoesNotChangeLegacyEffectiveBounds() {
        var plan = plan(Map.of("floor_cornice", true, "floor_height", 4));
        var input = List.of(wall(0, 0, 0), wall(4, 7, 4), wall(0, 3, 0), wall(-20, -10, -20),
            new BlockPatch(BlockPatch.REMOVE, -20, -10, -20, "minecraft:air"));
        var result = PatchTestSnapshot.blocks(processor.process(input, PostProcessContext.create(plan, BlockPos.ORIGIN)));
        assertTrue(result.get(new Vec3i(0, 3, 0)).contains("half=top"));
        assertFalse(result.containsKey(new Vec3i(-20, -10, -20)));
    }
    @Test void overwrittenOperationsDoNotConsumeReplacementBudget() {
        var plan = plan(Map.of("floor_cornice", true));
        var input = new ArrayList<BlockPatch>();
        for (int i = 0; i < 2600; i++) input.add(wall(0, 3, 0));
        input.add(wall(4, 3, 4));
        var output = processor.process(input, PostProcessContext.create(plan, BlockPos.ORIGIN,
            List.of(volume("main", 0, 0, 0, 8, 4))));
        var result = PatchTestSnapshot.blocks(output);
        assertTrue(result.get(new Vec3i(0, 3, 0)).contains("half=top"));
        assertTrue(result.get(new Vec3i(4, 3, 4)).contains("half=top"));
        assertEquals(2, output.stream().filter(p -> p.targetBlock().contains("half=top")).count());
    }
    @Test void globalFloorHeightOverridesIndividualMassHeight() {
        var mass = new Component("MASS_MAIN", "main", new Vec3i(0, 0, 0), new Dimensions(5, 5, 9), List.of(),
            Map.of("floor_height", 3));
        assertEquals(4, com.formacraft.common.generation.component.util.ComponentFloorCorniceDecorator.resolveFloorHeight(
            plan(Map.of("floor_height", 4)), mass, 9));
    }
    @Test void missingGlobalHeightUsesThisMassRatherThanAnotherMassInPlan() {
        var first = new Component("MASS_MAIN", "a", new Vec3i(0, 0, 0), new Dimensions(5, 5, 9), List.of(), Map.of("floor_height", 3));
        var second = new Component("MASS_MAIN", "b", new Vec3i(0, 0, 0), new Dimensions(5, 5, 8), List.of(), Map.of("floor_height", 4));
        var plan = LlmPlanTestFixtures.builder().components(List.of(first, second)).build();
        assertEquals(4, com.formacraft.common.generation.component.util.ComponentFloorCorniceDecorator.resolveFloorHeight(plan, second, 8));
    }

}
