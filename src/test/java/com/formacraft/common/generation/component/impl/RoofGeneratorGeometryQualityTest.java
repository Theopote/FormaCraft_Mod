package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Roof geometry quality: double-eave seating, stair facing, ridge/slope materials.
 */
class RoofGeneratorGeometryQualityTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void withStairsFacingAppendsPropertyOnce() {
        assertEquals("minecraft:oak_stairs[facing=south]",
                RoofGenerator.withStairsFacing("minecraft:oak_stairs", "south"));
        assertEquals("minecraft:oak_stairs[facing=north]",
                RoofGenerator.withStairsFacing("minecraft:oak_stairs[facing=north]", "east"));
        assertEquals("minecraft:oak_slab",
                RoofGenerator.withStairsFacing("minecraft:oak_slab", "west"));
        assertEquals("west", RoofGenerator.outwardFacingFromEdges(0, 3, 10, 10));
        assertEquals("east", RoofGenerator.outwardFacingFromEdges(9, 3, 10, 10));
        assertEquals("north", RoofGenerator.outwardFacingFromEdges(4, 0, 10, 10));
        assertEquals("south", RoofGenerator.outwardFacingFromEdges(4, 9, 10, 10));
    }

    @Test
    void hipSlopeStairsCarryOutwardFacing() {
        List<BlockPatch> patches = generate(
                "hip",
                List.of(),
                Map.of("material", "dark_oak_stairs", "overhang", 0),
                12, 12, 5, 0, 8, 0
        );
        long faced = patches.stream()
                .filter(p -> p.targetBlock() != null)
                .filter(p -> p.targetBlock().contains("stairs"))
                .filter(p -> p.targetBlock().contains("facing="))
                .count();
        assertTrue(faced >= 8, "hip slopes should emit facing= stairs, faced=" + faced);

        // West edge column should prefer facing=west on slope stairs.
        long westFacing = patches.stream()
                .filter(p -> p.dx() == 0)
                .filter(p -> p.targetBlock() != null && p.targetBlock().contains("facing=west"))
                .count();
        assertTrue(westFacing >= 1, "west edge should face west");
    }

    @Test
    void doubleEaveMidShelfSeatsBelowUpperTierWithFacedLip() {
        int baseY = 6;
        int height = 6;
        List<BlockPatch> patches = generate(
                "xieshan",
                List.of("重檐"),
                Map.of("material", "dark_oak_stairs", "overhang", 1),
                14, 14, height, 0, baseY, 0
        );
        assertFalse(patches.isEmpty());

        int upperHeight = Math.max(2, height / 2);
        int upperBaseY = baseY + (height - upperHeight);
        int expectedMidY = Math.max(baseY, upperBaseY - 1);

        long midSlabs = patches.stream()
                .filter(p -> p.dy() == expectedMidY)
                .filter(p -> p.targetBlock() != null && p.targetBlock().contains("slab"))
                .count();
        long midFacedLips = patches.stream()
                .filter(p -> p.dy() == expectedMidY)
                .filter(p -> p.targetBlock() != null
                        && p.targetBlock().contains("stairs")
                        && p.targetBlock().contains("facing="))
                .count();
        assertTrue(midSlabs >= 4, "mid-eave inner rim slabs at y=" + expectedMidY + " slabs=" + midSlabs);
        assertTrue(midFacedLips >= 4, "mid-eave outer lip needs facing stairs, lips=" + midFacedLips);

        // Upper tier mass should start at/above upperBaseY; mid shelf stays strictly below that band.
        int maxY = patches.stream().mapToInt(BlockPatch::dy).max().orElse(0);
        assertTrue(maxY >= upperBaseY, "upper tier should reach upperBaseY");
        assertTrue(expectedMidY < upperBaseY || height <= 2,
                "mid eave should seat under upper attachment");
    }

    @Test
    void doubleEaveHasDistinctLowerAndUpperHeightBands() {
        List<BlockPatch> patches = generate(
                "hip",
                List.of("double_eave"),
                Map.of("material", "stone_brick_stairs", "overhang", 1),
                16, 12, 8, 0, 10, 0
        );
        Set<Integer> ys = new HashSet<>();
        for (BlockPatch p : patches) ys.add(p.dy());
        assertTrue(ys.size() >= 4, "layered eaves need multiple Y bands, ys=" + ys);

        Map<Long, Integer> maxYByColumn = new HashMap<>();
        for (BlockPatch p : patches) {
            if (!BlockPatch.PLACE.equals(p.action())) continue;
            long key = (((long) p.dx()) << 32) ^ (p.dz() & 0xffffffffL);
            maxYByColumn.merge(key, p.dy(), Math::max);
        }
        int minTop = maxYByColumn.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        int maxTop = maxYByColumn.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        assertTrue(maxTop - minTop >= 2, "pitched double-eave tops should vary in height");
    }

    @Test
    void materialParamAffectsFinalLandingBlocks() {
        List<BlockPatch> oak = generate(
                "hip", List.of(), Map.of("material", "oak_stairs"), 8, 8, 4, 0, 4, 0);
        List<BlockPatch> spruce = generate(
                "hip", List.of(), Map.of("material", "spruce_stairs"), 8, 8, 4, 0, 4, 0);
        assertTrue(oak.stream().anyMatch(p -> p.targetBlock() != null
                && p.targetBlock().toLowerCase(Locale.ROOT).contains("oak")));
        assertTrue(spruce.stream().anyMatch(p -> p.targetBlock() != null
                && p.targetBlock().toLowerCase(Locale.ROOT).contains("spruce")));
        assertFalse(
                oak.stream().allMatch(p -> spruce.stream().anyMatch(s ->
                        s.dx() == p.dx() && s.dy() == p.dy() && s.dz() == p.dz()
                                && java.util.Objects.equals(s.targetBlock(), p.targetBlock()))),
                "oak vs spruce materials must change landing block ids");
    }

    private static List<BlockPatch> generate(
            String roofType,
            List<String> features,
            Map<String, Object> extraParams,
            int w, int d, int h,
            int x, int y, int z
    ) {
        Map<String, Object> params = new HashMap<>(extraParams);
        params.put("roof_type", roofType);
        Component roof = new Component(
                "ROOF",
                "hall",
                new Vec3i(x, y, z),
                new Dimensions(w, d, h),
                features,
                params
        );
        return new RoofGenerator().generate(new SemanticComponent("ROOF", null, roof, "ASIAN"));
    }
}
