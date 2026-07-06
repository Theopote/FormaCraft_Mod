package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DetailRulePostProcessorTest {

    @Test
    void appliesFloorCornicePresetOnPerimeterFloorBoundary() {
        Map<String, Object> hints = new HashMap<>();
        hints.put("typology", "classical_monument");
        hints.put("floor_cornice", true);
        hints.put("floor_height", 4);

        Component mass = new Component(
                "MASS_MAIN",
                "s0",
                new Vec3i(0, 0, 0),
                new Dimensions(5, 5, 8),
                List.of(),
                Map.of("floor_height", 4, "facade_profile", "vertical_pilasters")
        );

        LlmPlan plan = new LlmPlan(
                null, "MEDIEVAL_CLASSIC", null, null,
                new Layout(null, false, List.of()),
                List.of(mass), null, null,
                hints, null, null, null, null, null, null, null, null, null, null
        );

        List<BlockPatch> input = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 3, 0, "minecraft:stone_bricks"),
                new BlockPatch(BlockPatch.PLACE, 2, 3, 0, "minecraft:stone_bricks"),
                new BlockPatch(BlockPatch.PLACE, 4, 3, 0, "minecraft:stone_bricks"),
                new BlockPatch(BlockPatch.PLACE, 2, 3, 2, "minecraft:stone_bricks")
        );

        PostProcessContext ctx = PostProcessContext.create(plan, BlockPos.ORIGIN);
        List<BlockPatch> out = new DetailRulePostProcessor().process(input, ctx);

        boolean hasCornice = out.stream()
                .anyMatch(p -> p.targetBlock() != null
                        && p.targetBlock().contains("stairs")
                        && p.targetBlock().contains("half=top")
                        && p.dy() == 3
                        && p.dz() == 0);
        assertTrue(hasCornice);
    }

    @Test
    void appliesExplicitRoofEaveSlabRule() {
        Map<String, Object> hints = Map.of(
                "detail_rules", List.of(
                        Map.of(
                                "when", Map.of("region", "perimeter", "y", "roof_eave", "block", "wall"),
                                "action", Map.of("replace_with", "slab", "part", "WALL_ACCENT")
                        )
                )
        );

        LlmPlan plan = new LlmPlan(
                null, "MEDIEVAL_CLASSIC", null, null,
                new Layout(null, false, List.of()),
                List.of(), null, null,
                hints, null, null, null, null, null, null, null, null, null, null
        );

        List<BlockPatch> input = List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 5, 0, "minecraft:stone_bricks"),
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:stone_bricks"),
                new BlockPatch(BlockPatch.PLACE, 2, 3, 2, "minecraft:stone_bricks")
        );

        PostProcessContext ctx = PostProcessContext.create(plan, BlockPos.ORIGIN);
        List<BlockPatch> out = new DetailRulePostProcessor().process(input, ctx);

        boolean hasEaveSlab = out.stream()
                .anyMatch(p -> p.dx() == 0 && p.dy() == 5 && p.dz() == 0
                        && p.targetBlock() != null && p.targetBlock().contains("slab"));
        assertTrue(hasEaveSlab);
    }
}
