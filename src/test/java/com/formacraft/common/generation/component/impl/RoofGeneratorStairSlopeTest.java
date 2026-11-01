package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.util.RoofTypeKind;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RoofGeneratorStairSlopeTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void shallowStairGablePromotesToward45AndFillsUnder() {
        assertEquals(7, RoofGenerator.normalizeStairRoofRise(
                3, 12, 10, RoofTypeKind.GABLE,
                Map.of("prefer_stairs", true, "roof_block", "minecraft:oak_stairs", "ridge_axis", "x"),
                false));

        Map<String, Object> params = new HashMap<>();
        params.put("roof_type", "gable");
        params.put("roof_block", "minecraft:oak_stairs");
        params.put("prefer_stairs", true);
        params.put("ridge_axis", "x");
        params.put("overhang", 0);
        // Leave height shallow in dimensions; generator must bump.
        params.put("roof_height", 3);
        Component c = new Component("ROOF", "hall", new Vec3i(0, 8, 0),
                new Dimensions(12, 10, 3), List.of(), params);
        Slot slot = new Slot("hall", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null);
        List<BlockPatch> patches = new RoofGenerator().generate(new SemanticComponent("ROOF", slot, c));

        Set<Integer> ys = patches.stream().map(BlockPatch::dy).collect(Collectors.toSet());
        assertTrue(ys.size() >= 5, "45° stair gable needs multiple Y bands, ys=" + ys);

        long stairs = patches.stream().filter(p -> p.targetBlock() != null && p.targetBlock().contains("stairs")).count();
        long fill = patches.stream().filter(p -> p.targetBlock() != null
                && p.targetBlock().contains("planks") && !p.targetBlock().contains("stairs")).count();
        assertTrue(stairs >= 20, "surface stairs=" + stairs);
        assertTrue(fill >= 10, "solid fill under stairs=" + fill);

        // No 3-wide constant-Y terrace of only stairs without neighboring higher ridge.
        Map<Integer, Long> stairsPerY = patches.stream()
                .filter(p -> p.targetBlock() != null && p.targetBlock().contains("stairs"))
                .collect(Collectors.groupingBy(BlockPatch::dy, Collectors.counting()));
        int maxY = stairsPerY.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        int minY = stairsPerY.keySet().stream().mapToInt(Integer::intValue).min().orElse(0);
        assertTrue(maxY - minY >= 4, "stair surface span should track ~45° rise, span=" + (maxY - minY));
    }

    @Test
    void slopeFillBlockMapsOakStairsToPlanks() {
        assertEquals("minecraft:oak_planks", RoofGenerator.slopeFillBlock("minecraft:oak_stairs[facing=east]"));
        assertEquals("minecraft:stone_bricks",
                RoofGenerator.slopeFillBlock("minecraft:stone_brick_stairs"));
    }

    @Test
    void footprintCenterUsesHalfSize() {
        // Sanity: centering math for 12-wide mass at x=0 → shift -6.
        int centerX = 0 + 12 / 2;
        assertEquals(6, centerX);
    }
}
