package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WallFenceLineTest {

    @Test
    void oakFenceWallBlockEmitsLinearFenceNotSolidBox() {
        var wall = new Component(
                "WALL", "s", new Vec3i(0, 0, 0), new Dimensions(12, 4, 3), List.of(),
                Map.of("wall_block", "minecraft:oak_fence", "anchor_mode", "corner"));
        var patches = new WallComponentGenerator().generate(new SemanticComponent("WALL", null, wall));
        assertFalse(patches.isEmpty());
        assertTrue(patches.size() <= 24, "fence should be a thin line, not solid fill; got " + patches.size());
        assertTrue(patches.stream().allMatch(p -> p.targetBlock().contains("oak_fence")));
        assertEquals(Set.of(0, 1), patches.stream().map(BlockPatch::dy).collect(Collectors.toSet()));
        // Longer axis is X → single-file along X, z fixed
        assertEquals(1, patches.stream().map(BlockPatch::dz).distinct().count());
        assertEquals(12, patches.stream().map(BlockPatch::dx).distinct().count());
    }

    @Test
    void railingFeatureWithoutFenceMaterialStillUsesFenceLine() {
        var wall = new Component(
                "WALL", "s", new Vec3i(0, 0, 0), new Dimensions(8, 2, 1), List.of("wooden railing"),
                Map.of("anchor_mode", "corner"));
        var patches = new WallComponentGenerator().generate(new SemanticComponent("WALL", null, wall));
        assertEquals(8, patches.size());
        assertTrue(patches.stream().allMatch(p -> p.targetBlock().contains("oak_fence")));
    }
}
