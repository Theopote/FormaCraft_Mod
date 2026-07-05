package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
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

class WindowOrderPostProcessorTest {

    @Test
    void addsSurroundPatchesAroundPerimeterGlass() {
        Map<String, Object> hints = new HashMap<>();
        hints.put("typology", "classical_monument");
        hints.put("window_order", "full");

        Component facade = new Component(
                "FACADE_WINDOWS",
                "s0",
                new Vec3i(0, 0, 0),
                new Dimensions(9, 1, 8),
                List.of("facade_rhythm"),
                Map.of("window_order", "full", "window_aspect", "vertical_bay")
        );

        LlmPlan plan = new LlmPlan(
                null, "MEDIEVAL_CLASSIC", null,
                new GlobalConstraints(
                        GlobalConstraints.Facing.SOUTH,
                        GlobalConstraints.Symmetry.NONE,
                        GlobalConstraints.TerrainStrategy.ADAPTIVE),
                new Layout(null, false, List.of()),
                List.of(facade), null, null,
                hints, null, null, null, null, null, null, null, null, null
        );

        List<BlockPatch> input = List.of(
                new BlockPatch(BlockPatch.PLACE, 5, 2, 0, "minecraft:glass"),
                new BlockPatch(BlockPatch.PLACE, 6, 2, 0, "minecraft:glass"),
                new BlockPatch(BlockPatch.PLACE, 7, 2, 0, "minecraft:glass"),
                new BlockPatch(BlockPatch.PLACE, 5, 3, 0, "minecraft:glass_pane"),
                new BlockPatch(BlockPatch.PLACE, 6, 3, 0, "minecraft:glass_pane"),
                new BlockPatch(BlockPatch.PLACE, 7, 3, 0, "minecraft:glass_pane")
        );

        List<BlockPatch> out = new WindowOrderPostProcessor().process(
                input, PostProcessContext.create(plan, BlockPos.ORIGIN));

        assertTrue(out.size() > input.size());
        assertTrue(out.stream().anyMatch(p -> p.targetBlock() != null && p.targetBlock().contains("slab")));
        assertTrue(out.stream().anyMatch(p -> p.targetBlock() != null && p.targetBlock().contains("stairs")));
    }
}
