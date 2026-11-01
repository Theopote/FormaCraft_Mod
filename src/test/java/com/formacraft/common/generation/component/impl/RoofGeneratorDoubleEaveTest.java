package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RoofGeneratorDoubleEaveTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void chineseChongYanFeatureTriggersDoubleEaveAndMidShelf() {
        Component roof = new Component(
                "ROOF",
                "hall",
                new Vec3i(0, 6, 0),
                new Dimensions(12, 12, 6),
                List.of("重檐", "flying_eaves"),
                Map.of(
                        "roof_type", "hip",
                        "material", "dark_oak_stairs",
                        "overhang", 1
                )
        );
        List<BlockPatch> patches = new RoofGenerator().generate(
                new SemanticComponent("ROOF", null, roof, "ASIAN"));
        assertFalse(patches.isEmpty());

        Set<Integer> ys = new HashSet<>();
        for (BlockPatch p : patches) {
            if (BlockPatch.PLACE.equals(p.action())) ys.add(p.dy());
        }
        assertTrue(ys.size() >= 3, "double eave should span multiple Y bands, ys=" + ys);

        // Mid-eave: inner rim slabs + outer lip (stairs family).
        long slabs = patches.stream()
                .filter(p -> p.targetBlock() != null && p.targetBlock().contains("slab"))
                .count();
        long stairs = patches.stream()
                .filter(p -> p.targetBlock() != null && p.targetBlock().contains("stairs"))
                .count();
        assertTrue(slabs >= 8, "mid-eave shelf should place slab cornice, slabs=" + slabs);
        assertTrue(stairs >= 8, "mid-eave outer lip should use stairs, stairs=" + stairs);
    }

    @Test
    void xieshanDoubleEaveKeepsInsetUpperTier() {
        Component roof = new Component(
                "ROOF",
                "nave",
                new Vec3i(0, 10, 0),
                new Dimensions(16, 12, 8),
                List.of("double_eave"),
                Map.of(
                        "roof_type", "xieshan",
                        "material", "stone_brick_stairs",
                        "overhang", 1
                )
        );
        List<BlockPatch> patches = new RoofGenerator().generate(new SemanticComponent("ROOF", null, roof));
        assertFalse(patches.isEmpty());
        int minX = patches.stream().mapToInt(BlockPatch::dx).min().orElse(0);
        int maxX = patches.stream().mapToInt(BlockPatch::dx).max().orElse(0);
        int minZ = patches.stream().mapToInt(BlockPatch::dz).min().orElse(0);
        int maxZ = patches.stream().mapToInt(BlockPatch::dz).max().orElse(0);
        assertTrue(maxX - minX + 1 >= 16, "lower eave span");
        assertTrue(maxZ - minZ + 1 >= 12, "lower eave depth");

        long slabs = patches.stream()
                .filter(p -> p.targetBlock() != null && p.targetBlock().contains("slab"))
                .count();
        assertTrue(slabs >= 8, "xieshan double eave mid-shelf, slabs=" + slabs);
        assertTrue(patches.stream().mapToInt(BlockPatch::dy).distinct().count() >= 3);
    }
}
