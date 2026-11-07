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
import java.util.Optional;
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
    void withStairsFacingMergesFacingWithoutDroppingSiblingProps() {
        assertEquals("minecraft:oak_stairs[facing=south]",
                RoofGenerator.withStairsFacing("minecraft:oak_stairs", "south"));
        // Existing facing is overwritten; unrelated props (half) are kept.
        assertEquals("minecraft:oak_stairs[facing=east,half=top]",
                RoofGenerator.withStairsFacing("minecraft:oak_stairs[half=top]", "east"));
        assertEquals("minecraft:oak_stairs[facing=east,half=top]",
                RoofGenerator.withStairsFacing("minecraft:oak_stairs[facing=north,half=top]", "east"));
        assertEquals("minecraft:oak_stairs[facing=west,shape=outer_left]",
                RoofGenerator.withStairsFacing("minecraft:oak_stairs", "west", "outer_left"));
        assertEquals("minecraft:oak_slab",
                RoofGenerator.withStairsFacing("minecraft:oak_slab", "west"));
        assertEquals("west", RoofGenerator.outwardFacingFromEdges(0, 3, 10, 10));
        assertEquals("east", RoofGenerator.outwardFacingFromEdges(9, 3, 10, 10));
        assertEquals("north", RoofGenerator.outwardFacingFromEdges(4, 0, 10, 10));
        assertEquals("south", RoofGenerator.outwardFacingFromEdges(4, 9, 10, 10));
        // True corner prefers E/W over N/S.
        assertEquals("west", RoofGenerator.outwardFacingFromEdges(0, 0, 10, 10));
        assertEquals("east", RoofGenerator.outwardFacingFromEdges(9, 0, 10, 10));
    }

    @Test
    void hipCornersEmitOuterShapeAndFacing() {
        // ASIAN hip forces overhang≥1, so eave corners are at the expanded AABB, not core (0,0).
        List<BlockPatch> patches = generate(
                "hip",
                List.of(),
                Map.of("material", "stone_brick_stairs", "overhang", 0),
                12, 12, 5, 0, 8, 0
        );
        List<BlockPatch> stairs = patches.stream()
                .filter(p -> p.targetBlock() != null && p.targetBlock().contains("stairs"))
                .toList();
        int minX = stairs.stream().mapToInt(BlockPatch::dx).min().orElse(0);
        int maxX = stairs.stream().mapToInt(BlockPatch::dx).max().orElse(0);
        int minZ = stairs.stream().mapToInt(BlockPatch::dz).min().orElse(0);
        int maxZ = stairs.stream().mapToInt(BlockPatch::dz).max().orElse(0);
        int[][] corners = {{minX, minZ}, {minX, maxZ}, {maxX, minZ}, {maxX, maxZ}};
        for (int[] c : corners) {
            List<BlockPatch> at = stairs.stream()
                    .filter(p -> p.dx() == c[0] && p.dz() == c[1])
                    .toList();
            assertFalse(at.isEmpty(), "corner " + c[0] + "," + c[1] + " should have stairs");
            String block = at.get(0).targetBlock();
            assertTrue(block.contains("facing="), "corner needs facing: " + block);
            assertTrue(block.contains("shape=outer_"), "corner needs outer miter shape: " + block);
        }
    }

    @Test
    void hipSlopeStairsFaceTowardRidge() {
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

        // West edge: higher half toward ridge ⇒ facing=east.
        long westEdgeFacesEast = patches.stream()
                .filter(p -> p.dx() == 0)
                .filter(p -> p.targetBlock() != null && p.targetBlock().contains("facing=east"))
                .count();
        assertTrue(westEdgeFacesEast >= 1, "west edge should face east toward ridge");
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

    @Test
    void gableSlopeStairsFaceTowardRidge() {
        // depth >= width ⇒ ridge along Z; higher half faces inward (west pitch → east).
        List<BlockPatch> patches = generate(
                "gable",
                List.of(),
                Map.of("material", "oak_stairs", "overhang", 0),
                10, 14, 5, 0, 8, 0
        );
        List<BlockPatch> stairs = patches.stream()
                .filter(p -> p.targetBlock() != null && p.targetBlock().contains("stairs"))
                .filter(p -> p.targetBlock().contains("facing="))
                .toList();
        assertFalse(stairs.isEmpty(), "gable slopes need faced stairs");
        int midX = stairs.stream().mapToInt(BlockPatch::dx).max().orElse(0) / 2;
        long west = stairs.stream().filter(p -> p.dx() < midX && p.targetBlock().contains("facing=east")).count();
        long east = stairs.stream().filter(p -> p.dx() > midX && p.targetBlock().contains("facing=west")).count();
        assertTrue(west >= 2, "west pitch facing=east toward ridge, west=" + west);
        assertTrue(east >= 2, "east pitch facing=west toward ridge, east=" + east);
        // Full column coverage over the authored footprint (ASIAN xuanshan/gable may expand eaves).
        Set<Long> columns = new HashSet<>();
        for (BlockPatch p : patches) {
            if (!BlockPatch.PLACE.equals(p.action())) continue;
            columns.add((((long) p.dx()) << 32) ^ (p.dz() & 0xffffffffL));
        }
        assertTrue(columns.size() >= 10 * 14, "gable must cover footprint columns, cols=" + columns.size());
    }

    @Test
    void xieshanMidSpanFacesLikeGableWhileCornersKeepOuterMiter() {
        // Wide-deep hall: ridge along depth; mid-span should face E/W like a gable, not flip to N/S.
        List<BlockPatch> patches = generate(
                "xieshan",
                List.of(),
                Map.of("material", "dark_oak_stairs", "overhang", 1),
                12, 16, 6, 0, 8, 0
        );
        List<BlockPatch> stairs = patches.stream()
                .filter(p -> p.targetBlock() != null && p.targetBlock().contains("stairs"))
                .toList();
        int minX = stairs.stream().mapToInt(BlockPatch::dx).min().orElse(0);
        int maxX = stairs.stream().mapToInt(BlockPatch::dx).max().orElse(0);
        int minZ = stairs.stream().mapToInt(BlockPatch::dz).min().orElse(0);
        int maxZ = stairs.stream().mapToInt(BlockPatch::dz).max().orElse(0);
        int midZ = (minZ + maxZ) / 2;
        // Mid-span west pitch (away from hip ends).
        long midWest = stairs.stream()
                .filter(p -> p.dx() == minX + 1 && Math.abs(p.dz() - midZ) <= 1)
                .filter(p -> p.targetBlock().contains("facing=east"))
                .count();
        assertTrue(midWest >= 1, "xieshan mid-span west pitch should face east (toward ridge)");

        int[][] corners = {{minX, minZ}, {minX, maxZ}, {maxX, minZ}, {maxX, maxZ}};
        for (int[] c : corners) {
            Optional<BlockPatch> at = stairs.stream()
                    .filter(p -> p.dx() == c[0] && p.dz() == c[1])
                    .findFirst();
            assertTrue(at.isPresent(), "xieshan corner " + c[0] + "," + c[1]);
            assertTrue(at.get().targetBlock().contains("shape=outer_"),
                    "corner miter: " + at.get().targetBlock());
        }
    }

    @Test
    void doubleEaveMidLipCornersCarryOuterShape() {
        List<BlockPatch> patches = generate(
                "hip",
                List.of("double_eave"),
                Map.of("material", "stone_brick_stairs", "overhang", 1),
                16, 14, 8, 0, 10, 0
        );
        int height = 8;
        int baseY = 10;
        int upperHeight = Math.max(2, height / 2);
        int upperBaseY = baseY + (height - upperHeight);
        int midY = Math.max(baseY, upperBaseY - 1);
        List<BlockPatch> midLips = patches.stream()
                .filter(p -> p.dy() == midY)
                .filter(p -> p.targetBlock() != null
                        && p.targetBlock().contains("stairs")
                        && p.targetBlock().contains("facing="))
                .toList();
        assertTrue(midLips.size() >= 4, "mid lip stairs=" + midLips.size());
        int minX = midLips.stream().mapToInt(BlockPatch::dx).min().orElse(0);
        int maxX = midLips.stream().mapToInt(BlockPatch::dx).max().orElse(0);
        int minZ = midLips.stream().mapToInt(BlockPatch::dz).min().orElse(0);
        int maxZ = midLips.stream().mapToInt(BlockPatch::dz).max().orElse(0);
        // Mid shelf is emitted after the lower tier; apply last-wins at each corner cell.
        for (int[] c : new int[][]{{minX, minZ}, {minX, maxZ}, {maxX, minZ}, {maxX, maxZ}}) {
            List<BlockPatch> at = patches.stream()
                    .filter(p -> p.dy() == midY && p.dx() == c[0] && p.dz() == c[1])
                    .filter(p -> p.targetBlock() != null && p.targetBlock().contains("stairs"))
                    .toList();
            assertFalse(at.isEmpty(), "mid-eave corner " + c[0] + "," + c[1]);
            String landed = at.get(at.size() - 1).targetBlock();
            assertTrue(landed.contains("facing="), "mid-eave corner facing: " + landed);
            assertTrue(landed.contains("shape=outer_"), "mid-eave corner miter: " + landed);
        }
    }

    @Test
    void hexagonPyramidUsesSixSlopeApexNotChebyshev() {
        // Mid-edge of a hexagon (top of flat-top) is farther from corners than AABB Chebyshev implies.
        double apexMidEdge = com.formacraft.common.generation.component.util.ResolvedFootprint
                .hexagonApexFactor(5, 0, 11, 11);
        double apexCorner = com.formacraft.common.generation.component.util.ResolvedFootprint
                .hexagonApexFactor(0, 0, 11, 11);
        assertTrue(apexMidEdge > 0.0, "mid-edge is on/near hex boundary with small apex");
        assertEquals(0.0, apexCorner, 1e-9, "AABB corner is outside hex");
        double center = com.formacraft.common.generation.component.util.ResolvedFootprint
                .hexagonApexFactor(5, 5, 11, 11);
        assertTrue(center > apexMidEdge, "center must rise above edge facets");

        List<BlockPatch> patches = generate(
                "pyramid",
                List.of(),
                Map.of("material", "oak_stairs", "overhang", 0, "shape", "hexagon"),
                11, 11, 6, 0, 5, 0
        );
        assertFalse(patches.isEmpty());
        // Corner cells of the AABB must not be roofed (outside hex).
        assertTrue(patches.stream().noneMatch(p -> p.dx() == 0 && p.dz() == 0));
        assertTrue(patches.stream().noneMatch(p -> p.dx() == 10 && p.dz() == 10));
        // Center column reaches higher than a mid-edge cell.
        int centerY = patches.stream().filter(p -> p.dx() == 5 && p.dz() == 5)
                .mapToInt(BlockPatch::dy).max().orElse(-1);
        int edgeY = patches.stream().filter(p -> p.dx() == 5 && p.dz() == 0)
                .mapToInt(BlockPatch::dy).max().orElse(-1);
        assertTrue(centerY > edgeY, "centerY=" + centerY + " edgeY=" + edgeY);
        assertEquals("east", RoofGenerator.hexagonInwardFacing(0, 5, 11, 11));
        assertEquals("west", RoofGenerator.hexagonInwardFacing(10, 5, 11, 11));
    }

    @Test
    void fourRoofKindsCoverFootprintWithoutConflictingCellIds() {
        for (String type : List.of("gable", "hip", "xieshan")) {
            List<BlockPatch> patches = generate(
                    type,
                    List.of(),
                    Map.of("material", "oak_stairs", "overhang", 1),
                    12, 10, 5, 0, 6, 0
            );
            Map<String, String> cellToBlock = new HashMap<>();
            int conflicts = 0;
            for (BlockPatch p : patches) {
                if (!BlockPatch.PLACE.equals(p.action()) || p.targetBlock() == null) continue;
                String key = p.dx() + "," + p.dy() + "," + p.dz();
                String prev = cellToBlock.put(key, p.targetBlock());
                if (prev != null && !prev.equals(p.targetBlock())) conflicts++;
            }
            assertFalse(patches.isEmpty(), type + " empty");
            assertTrue(cellToBlock.size() >= 12 * 10,
                    type + " coverage cells=" + cellToBlock.size());
            // Later layers (seal/specialty) may overwrite; hard conflict budget stays low.
            assertTrue(conflicts <= cellToBlock.size() / 4,
                    type + " conflicting overwrites=" + conflicts + "/" + cellToBlock.size());
        }
        List<BlockPatch> doubleEave = generate(
                "hip",
                List.of("double_eave"),
                Map.of("material", "oak_stairs", "overhang", 1),
                14, 12, 8, 0, 8, 0
        );
        assertTrue(doubleEave.stream().mapToInt(BlockPatch::dy).distinct().count() >= 4);
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
