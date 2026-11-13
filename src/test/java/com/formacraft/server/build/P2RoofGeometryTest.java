package com.formacraft.server.build;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.generation.component.util.*;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class P2RoofGeometryTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }

    private Component roof(String type, int span, int height) {
        return new Component("ROOF", "hall", new Vec3i(0,20,0), new Dimensions(span,span+4,height),
                List.of(), Map.of("roof_type",type,"roof_block","minecraft:hay_block","gable_walls",false));
    }

    @Test void bellcastHasShallowEavesSteepUpperSlopesAndHollowInterior() {
        var generator = new RoofGenerator();
        var patches = generator.generate(new SemanticComponent("ROOF", null, roof("钟形双坡顶", 13, 13)));
        assertEquals(List.of(20,21,22,25,27,30,32,30,27,25,22,21,20), section(patches,13));
        assertFalse(patches.stream().anyMatch(p -> p.dx()==6 && p.dz()==8 && p.dy()==20));
        for (int y=22; y<25; y++) {
            int level=y;
            assertTrue(patches.stream().anyMatch(p -> p.dx()==3 && p.dz()==8 && p.dy()==level));
        }
        var custom = new Component("ROOF", "hall", new Vec3i(0,20,0), new Dimensions(17,21,9), List.of(),
                Map.of("roof_type","bellcast", "roof_block","minecraft:hay_block", "gable_walls",false,
                        "ridge_axis","z", "bellcast_break_run_fraction",0.5, "bellcast_break_rise_fraction",0.2));
        var adjusted=generator.generate(new SemanticComponent("ROOF",null,custom));
        assertEquals(22,adjusted.stream().filter(p -> p.dx()==4 && p.dz()==8).mapToInt(BlockPatch::dy).max().orElseThrow());
        assertEquals(28,adjusted.stream().mapToInt(BlockPatch::dy).max().orElseThrow());
        for (double rise : List.of(0.5, Double.NaN, -0.1, 1.0)) {
            var invalid = new Component("ROOF", "hall", new Vec3i(0,20,0), new Dimensions(17,21,9), List.of(),
                    Map.of("roof_type","bellcast", "bellcast_break_run_fraction",0.5,
                            "bellcast_break_rise_fraction",rise));
            assertThrows(IllegalArgumentException.class, () -> generator.generate(new SemanticComponent("ROOF",null,invalid)));
        }
        assertEquals(RoofTypeKind.BELLCAST, RoofTypeCatalog.parseExplicit("bell_cast").orElseThrow());
    }

    @Test void allSpecialtyRoofsExecuteAtTwoScalesWithoutFillingInterior() {
        for (String type : List.of("gambrel","barrel_vault","onion_dome","steep_thatch")) {
            for (int span : List.of(13,21)) {
                int height=span;
                var patches=new RoofGenerator().generate(new SemanticComponent("ROOF",null,roof(type,span,height)));
                assertFalse(patches.isEmpty(),type);
                assertEquals(20+height-1,patches.stream().mapToInt(BlockPatch::dy).max().orElseThrow(),type);
                assertFalse(patches.stream().anyMatch(p->p.dx()==span/2 && p.dz()==(span+4)/2 && p.dy()==20),type);
                assertTrue(patches.stream().allMatch(p->p.dx()>=0 && p.dx()<span && p.dz()>=0 && p.dz()<span+4),type);
            }
        }
    }

    @Test void profilesAreDistinctAndOnionBulgesAboveItsNeck() {
        var profiles=new HashSet<Set<?>>();
        for (String type:List.of("gambrel","barrel_vault","onion_dome","steep_thatch"))
            profiles.add(new HashSet<>(new RoofGenerator().generate(new SemanticComponent("ROOF",null,roof(type,21,21)))));
        assertEquals(4,profiles.size());
        var onion=SpecialtyRoofSurfaces.generate(RoofTypeKind.ONION_DOME,0,0,0,21,21,21,"minecraft:hay_block",null,0);
        int neck=onion.stream().filter(p->p.dy()==0).mapToInt(BlockPatch::dx).max().orElseThrow();
        assertTrue(onion.stream().filter(p->p.dy()>0).mapToInt(BlockPatch::dx).max().orElseThrow()>neck);
    }

    @Test void steepThatchRejectsShallowGeometryAndAliasesRemainExecutable() {
        assertThrows(IllegalArgumentException.class,()->SpecialtyRoofSurfaces.generate(
                RoofTypeKind.STEEP_THATCH,0,0,0,21,25,4,"minecraft:hay_block",null,0));
        for(String alias:List.of("折线双坡顶","筒拱屋顶","洋葱穹顶","陡坡茅草顶"))
            assertTrue(RoofTypeCatalog.parseExplicit(alias).isPresent(),alias);
    }

    @Test void hostedMansardPlacesDormersAtTheAuthoredFold() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var fixture = java.nio.file.Path.of("python_backend/eval/fixtures/plans/p2_gambrel_13.json");
        var document = mapper.readTree(fixture.toFile());
        var roofParams = (com.fasterxml.jackson.databind.node.ObjectNode) document.at("/components/1/params");
        roofParams.put("roof_type", "mansard").put("roof_block", "minecraft:stone_bricks")
                .put("roof_height", 11).put("mansard_break_run", 3).put("mansard_break_rise", 7)
                .put("mansard_dormer_count", 2);
        var plan = mapper.treeToValue(document, LlmPlan.class);
        var patches = com.formacraft.server.compiler.ComponentPlanCompiler.compile(plan,
                new net.minecraft.util.math.BlockPos(0, 64, 0), null, null, false);
        var roof = patches.stream().filter(p -> p.targetBlock() != null
                && p.targetBlock().startsWith("minecraft:stone_brick")).toList();
        assertEquals(List.of(10, 12, 15, 17, 18, 19, 20, 19, 18, 17, 15, 12, 10), section(roof, 13));
        // Depth>width: dormers sit on the long face (dx=setback-1), centered along Z.
        assertEquals(List.of(4, 12), patches.stream()
                .filter(p -> p.dx() == 2 && p.dy() == 18 && "minecraft:glass_pane".equals(p.targetBlock()))
                .map(BlockPatch::dz).sorted().toList());
    }

    @Test void hostedGambrelUsesItsAuthoredFoldAndSealsTheSteepLowerSlope() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var fixture = java.nio.file.Path.of("python_backend/eval/fixtures/plans/p2_gambrel_13.json");
        var document = mapper.readTree(fixture.toFile());
        var roofParams = (com.fasterxml.jackson.databind.node.ObjectNode) document.at("/components/1/params");
        roofParams.put("gambrel_break_run_fraction", 0.5).put("gambrel_break_rise_fraction", 0.75);
        var plan = mapper.treeToValue(document, LlmPlan.class);
        var patches = com.formacraft.server.compiler.ComponentPlanCompiler.compile(plan,
                new net.minecraft.util.math.BlockPos(0, 64, 0), null, null, false);
        var roof = patches.stream().filter(p -> "minecraft:hay_block".equals(p.targetBlock())).toList();
        assertEquals(List.of(10, 13, 16, 19, 20, 21, 22, 21, 20, 19, 16, 13, 10), section(roof, 13));
        for (int y = 13; y < 16; y++) {
            int level = y;
            assertTrue(roof.stream().anyMatch(p -> p.dx() == 2 && p.dz() == 8 && p.dy() == level));
        }
        assertFalse(roof.stream().anyMatch(p -> p.dx() == 6 && p.dz() == 8 && p.dy() == 10));
    }

    @Test void gambrelDefaultsPreserveTheOldSectionAndRejectAReversedFold() {
        var generator = new RoofGenerator();
        var defaultRoof = generator.generate(new SemanticComponent("ROOF", null, roof("gambrel", 13, 13)));
        assertEquals(List.of(20, 24, 27, 29, 30, 31, 32, 31, 30, 29, 27, 24, 20), section(defaultRoof, 13));
        var invalid = new Component("ROOF", "hall", new Vec3i(0, 20, 0), new Dimensions(13, 17, 13), List.of(),
                Map.of("roof_type", "gambrel", "gambrel_break_run_fraction", 0.7,
                        "gambrel_break_rise_fraction", 0.6));
        var error = assertThrows(IllegalArgumentException.class,
                () -> generator.generate(new SemanticComponent("ROOF", null, invalid)));
        assertTrue(error.getMessage().contains("gambrel_break_rise_fraction"));
    }

    @Test void profiledGablesPreserveRiseAndSealSteepRisers() {
        var generator = new RoofGenerator();
        var rounded = generator.generate(new SemanticComponent("ROOF", null, roof("卷棚顶", 13, 13)));
        var saltbox = generator.generate(new SemanticComponent("ROOF", null, roof("盐盒顶", 13, 13)));
        List<Integer> roundedSection = section(rounded, 13);
        List<Integer> saltboxSection = section(saltbox, 13);

        assertEquals(List.of(20, 23, 26, 28, 30, 32, 32, 32, 30, 28, 26, 23, 20), roundedSection);
        assertEquals(List.of(20, 23, 26, 29, 32, 31, 29, 28, 26, 25, 23, 22, 20), saltboxSection);
        for (var patches : List.of(rounded, saltbox)) {
            assertTrue(patches.stream().allMatch(p -> "minecraft:hay_block".equals(p.targetBlock())));
            assertTrue(patches.stream().anyMatch(p -> p.dx() == 1 && p.dy() == 20 && p.dz() == 8));
            assertFalse(patches.stream().anyMatch(p -> p.dx() == 4 && p.dy() == 20 && p.dz() == 8));
        }
    }

    @Test void saltboxLongSlopeCanFaceAllFourDirectionsWithAnAuthoredRidgeOffset() {
        var generator = new RoofGenerator();
        for (String facing : List.of("east", "west", "south", "north")) {
            boolean acrossX = facing.equals("east") || facing.equals("west");
            boolean mirrored = facing.equals("west") || facing.equals("north");
            int last = acrossX ? 12 : 16;
            int shortRun = last / 4;
            int ridge = mirrored ? last - shortRun : shortRun;
            var component = new Component("ROOF", "hall", new Vec3i(0, 20, 0), new Dimensions(13, 17, shortRun + 1),
                    List.of(), Map.of("roof_type", "saltbox", "roof_block", "minecraft:stone_bricks",
                            "saltbox_long_slope", facing, "saltbox_ridge_fraction", 0.25, "gable_walls", false));
            var patches = generator.generate(new SemanticComponent("ROOF", null, component));
            var section = patches.stream().filter(p -> acrossX ? p.dz() == 8 : p.dx() == 6).toList();
            int ridgeY = 20 + shortRun;
            var crest = section.stream().filter(p -> p.dy() == ridgeY)
                    .mapToInt(p -> acrossX ? p.dx() : p.dz()).summaryStatistics();
            assertEquals(ridge, mirrored ? crest.getMax() : crest.getMin(), facing);
            int shortEave = mirrored ? last : 0;
            int longEave = mirrored ? 0 : last;
            assertTrue(section.stream().anyMatch(p -> (acrossX ? p.dx() : p.dz()) == shortEave && p.dy() == 20
                    && p.targetBlock().startsWith("minecraft:stone_brick_stairs[")
                    && p.targetBlock().contains("facing=" + facing)), facing);
            assertTrue(section.stream().anyMatch(p -> (acrossX ? p.dx() : p.dz()) == longEave && p.dy() == 20
                    && p.targetBlock().equals("minecraft:stone_brick_slab[type=bottom]")), facing);
        }
    }

    @Test void saltboxRejectsInvalidDirectionAndRidgeFraction() {
        for (var invalid : List.of(Map.<String, Object>of("saltbox_long_slope", "up"),
                Map.<String, Object>of("saltbox_ridge_fraction", 0.5),
                Map.<String, Object>of("saltbox_ridge_fraction", "NaN"))) {
            var params = new HashMap<>(invalid);
            params.put("roof_type", "saltbox");
            var component = new Component("ROOF", "hall", new Vec3i(0, 20, 0), new Dimensions(13, 17, 6),
                    List.of(), params);
            var exception = assertThrows(IllegalArgumentException.class,
                    () -> new RoofGenerator().generate(new SemanticComponent("ROOF", null, component)));
            assertTrue(exception.getMessage().contains(invalid.keySet().iterator().next()));
        }
    }

    @Test void butterflyRoofHasASolidGutterAndClosesTheRaisedWallPerimeter() {
        for (String axis : List.of("x", "z")) {
            boolean alongDepth = axis.equals("z");
            int span = alongDepth ? 13 : 16;
            int last = span - 1;
            int run = last / 2;
            var component = new Component("ROOF", "hall", new Vec3i(0, 20, 0), new Dimensions(13, 16, run + 1),
                    List.of(), Map.of("roof_type", "蝶形顶", "roof_block", "minecraft:spruce_planks",
                            "wall_block", "minecraft:stone_bricks", "ridge_axis", axis));
            var patches = new RoofGenerator().generate(new SemanticComponent("ROOF", null, component));
            for (int across = 0; across < span; across++) {
                int x = alongDepth ? across : 6;
                int z = alongDepth ? 6 : across;
                var top = patches.stream().filter(p -> p.dx() == x && p.dz() == z)
                        .max(Comparator.comparingInt(BlockPatch::dy)).orElseThrow();
                int distanceToEdge = Math.min(across, last - across);
                assertEquals(20 + run - distanceToEdge, top.dy(), axis);
                if (distanceToEdge == run) assertEquals("minecraft:spruce_planks", top.targetBlock(), axis);
                if (distanceToEdge == 1) {
                    String facing = alongDepth ? (across == 1 ? "west" : "east")
                            : (across == 1 ? "north" : "south");
                    assertTrue(top.targetBlock().contains("facing=" + facing), axis);
                }
            }
            assertTrue(patches.stream().anyMatch(p -> p.dx() == (alongDepth ? 0 : 6)
                    && p.dz() == (alongDepth ? 6 : 0) && p.dy() == 20
                    && "minecraft:stone_bricks".equals(p.targetBlock())), axis);
            assertTrue(patches.stream().anyMatch(p -> p.dx() == (alongDepth ? 1 : 0)
                    && p.dz() == (alongDepth ? 0 : 1) && p.dy() == 20
                    && "minecraft:stone_bricks".equals(p.targetBlock())), axis);
            assertFalse(patches.stream().anyMatch(p -> p.dx() == (alongDepth ? 1 : 6)
                    && p.dz() == (alongDepth ? 6 : 1) && p.dy() == 20), axis);
        }
    }

    @Test void lShapedHipFollowsTheInnerEavesAndSealsTheValley() {
        for (String corner : List.of("NW", "NE", "SW", "SE")) {
            var component = new Component("ROOF", "hall", new Vec3i(0, 20, 0), new Dimensions(17, 17, 9),
                    List.of(), Map.of("roof_type", "hip", "roof_block", "minecraft:stone_bricks",
                            "plan_type", "l_shape", "l_cut", 7, "l_corner", corner, "overhang", 1,
                            "roof_slope_profile", "linear"));
            var patches = new RoofGenerator().generate(new SemanticComponent("ROOF", null, component));
            int[][] tops = new int[19][19];
            boolean joined = false;
            for (var patch : patches) {
                int x = corner.endsWith("E") ? 17 - patch.dx() : patch.dx() + 1;
                int z = corner.startsWith("S") ? 17 - patch.dz() : patch.dz() + 1;
                tops[x][z] = Math.max(tops[x][z], patch.dy());
                if (x == 8 && z == 7 && patch.dy() == 20) joined = true;
            }
            for (int x = 0; x < 19; x++) {
                for (int z = 0; z < 19; z++) {
                    assertEquals(x >= 7 || z >= 7, tops[x][z] > 0, corner + " " + x + "," + z);
                }
            }
            assertEquals(20, tops[7][7], corner);
            assertEquals(22, tops[8][8], corner);
            assertEquals(23, tops[8][9], corner);
            assertEquals(28, Arrays.stream(tops).flatMapToInt(Arrays::stream).max().orElseThrow(), corner);
            assertTrue(joined, corner);
        }
    }

    @Test void lShapedHipTilesItsSlopesButKeepsTheValleySolid() {
        for (String corner : List.of("NW", "NE", "SW", "SE")) {
            boolean east = corner.endsWith("E");
            boolean south = corner.startsWith("S");
            for (int height : List.of(3, 6)) {
                var component = new Component("ROOF", "hall", new Vec3i(0, 20, 0), new Dimensions(17, 17, height),
                        List.of(), Map.of("roof_type", "hip", "roof_block", "minecraft:stone_bricks",
                                "plan_type", "l_shape", "l_cut", 7, "l_corner", corner, "overhang", 1,
                                "roof_height", height, "roof_slope_profile", "linear"));
                var patches = new RoofGenerator().generate(new SemanticComponent("ROOF", null, component));
                int x = east ? 9 : 7;
                int valleyZ = south ? 9 : 7;
                var slope = patches.stream().filter(p -> p.dx() == x && p.dz() == 8)
                        .max(Comparator.comparingInt(BlockPatch::dy)).orElseThrow();
                var valley = patches.stream().filter(p -> p.dx() == x && p.dz() == valleyZ)
                        .max(Comparator.comparingInt(BlockPatch::dy)).orElseThrow();
                assertEquals("minecraft:stone_bricks", valley.targetBlock(), corner);
                if (height == 3) {
                    assertEquals("minecraft:stone_brick_slab[type=bottom]", slope.targetBlock(), corner);
                } else {
                    assertTrue(slope.targetBlock().startsWith("minecraft:stone_brick_stairs["), corner);
                    assertTrue(slope.targetBlock().contains(south ? "facing=north" : "facing=south"), corner);
                }
            }
        }
    }

    @Test void profiledGablesSelectMatchingRoofBlocksFromUnroundedSlope() {
        var generator = new RoofGenerator();
        for (String type : List.of("juanpeng", "saltbox")) {
            int height = type.equals("juanpeng") ? 6 : 5;
            var component = new Component("ROOF", "hall", new Vec3i(0, 20, 0), new Dimensions(13, 17, height),
                    List.of(), Map.of("roof_type", type, "roof_block", "minecraft:stone_bricks", "gable_walls", false));
            var patches = generator.generate(new SemanticComponent("ROOF", null, component));
            var section = section(patches, 13);
            assertEquals(20 + height - 1, Collections.max(section), type);
            var surfaces = new ArrayList<String>();
            for (int x = 0; x < section.size(); x++) {
                int column = x;
                surfaces.add(patches.stream()
                        .filter(p -> p.dx() == column && p.dz() == 8 && p.dy() == section.get(column))
                        .findFirst().orElseThrow().targetBlock());
            }

            assertTrue(surfaces.getFirst().startsWith("minecraft:stone_brick_stairs["));
            assertTrue(surfaces.getFirst().contains("facing=east"));
            assertTrue(surfaces.getFirst().contains("shape=straight"));
            assertEquals("minecraft:stone_bricks", surfaces.get(section.indexOf(20 + height - 1)));
            if (type.equals("juanpeng")) {
                assertTrue(surfaces.getLast().contains("facing=west"));
                assertEquals("minecraft:stone_brick_slab[type=bottom]", surfaces.get(4));
            } else {
                assertEquals("minecraft:stone_brick_slab[type=bottom]", surfaces.getLast());
            }
            // A full block beneath the first raised stair contacts the eave stair's lower half.
            assertTrue(patches.stream().anyMatch(p -> p.dx() == 1 && p.dz() == 8 && p.dy() == 20
                    && "minecraft:stone_bricks".equals(p.targetBlock())));
        }
    }

    @Test void juanpengVariantsHaveMatchingProfilesAndDistinctGableOverhangs() {
        for (String axis : List.of("x", "z")) {
            boolean alongDepth = axis.equals("z");
            var generator = new RoofGenerator();
            var hard = juanpengVariant("硬山卷棚顶", axis);
            var hanging = juanpengVariant("悬山卷棚顶", axis);
            var hardPatches = generator.generate(new SemanticComponent("ROOF", null, hard));
            var hangingPatches = generator.generate(new SemanticComponent("ROOF", null, hanging));
            int end = alongDepth ? 17 : 13;
            var cropped = hangingPatches.stream().filter(p -> {
                int along = alongDepth ? p.dz() : p.dx();
                return along >= 0 && along < end;
            }).toList();
            assertEquals(new HashSet<>(cropped), new HashSet<>(hardPatches), axis);
            assertTrue(hangingPatches.stream().anyMatch(p -> (alongDepth ? p.dz() : p.dx()) == -2
                    && p.dy() == 25), axis);
            assertTrue(hardPatches.stream().anyMatch(p -> (alongDepth ? p.dx() : p.dz()) == -2
                    && p.dy() == 20), axis);
            assertTrue(hardPatches.stream().anyMatch(p -> (alongDepth ? p.dz() : p.dx()) == 0
                    && p.dy() == 20 && "minecraft:stone_bricks".equals(p.targetBlock())), axis);
        }
    }

    private static Component juanpengVariant(String type, String axis) {
        return new Component("ROOF", "hall", new Vec3i(0, 20, 0), new Dimensions(13, 17, 6), List.of(),
                Map.of("roof_type", type, "roof_block", "minecraft:spruce_planks",
                        "wall_block", "minecraft:stone_bricks", "ridge_axis", axis, "overhang", 2));
    }

    @Test void profiledGableWallsFollowTheAuthoredRidgeAxis() {
        var component = new Component("ROOF", "hall", new Vec3i(0, 20, 0), new Dimensions(13, 17, 13),
                List.of(), Map.of("roof_type", "saltbox", "roof_block", "minecraft:hay_block",
                        "wall_block", "minecraft:stone_bricks", "ridge_axis", "x", "overhang", 1));
        var patches = new RoofGenerator().generate(new SemanticComponent("ROOF", null, component));
        assertTrue(patches.stream().anyMatch(p -> p.dx() == 6 && p.dy() == 32 && p.dz() == 5));
        assertTrue(patches.stream().anyMatch(p -> p.dx() == 0 && p.dy() == 20 && p.dz() == 5
                && "minecraft:stone_bricks".equals(p.targetBlock())));
        assertFalse(patches.stream().anyMatch(p -> p.dx() == 6 && p.dy() == 20 && p.dz() == 5));
    }

    private static List<Integer> section(List<BlockPatch> patches, int span) {
        var section = new Integer[span];
        Arrays.fill(section, Integer.MIN_VALUE);
        for (var patch : patches) {
            if (patch.dz() == 8 && patch.dx() >= 0 && patch.dx() < span) {
                int x = patch.dx();
                section[x] = Math.max(section[x], patch.dy());
            }
        }
        return List.of(section);
    }

    @Test void profiledGablesCompileOnAHostedMass() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var fixture = java.nio.file.Path.of("python_backend/eval/fixtures/plans/p2_gambrel_13.json");
        for (String type : List.of("juanpeng", "saltbox")) {
            var document = mapper.readTree(fixture.toFile());
            var roofParams = (com.fasterxml.jackson.databind.node.ObjectNode) document.at("/components/1/params");
            roofParams.put("roof_type", type);
            roofParams.put("roof_block", "minecraft:stone_bricks");
            int height = type.equals("saltbox") ? 5 : 6;
            var dimensions = (com.fasterxml.jackson.databind.node.ObjectNode) document.at("/components/1/dimensions");
            dimensions.put("height", height);
            var plan = mapper.treeToValue(document, LlmPlan.class);
            var patches = com.formacraft.server.compiler.ComponentPlanCompiler.compile(plan,
                    new net.minecraft.util.math.BlockPos(0, 64, 0), null, null, false);
            var roof = patches.stream()
                    .filter(p -> p.targetBlock() != null && p.targetBlock().startsWith("minecraft:stone_brick"))
                    .toList();
            assertTrue(roof.stream().anyMatch(p -> p.targetBlock().startsWith("minecraft:stone_brick_stairs[")), type);
            assertTrue(roof.stream().anyMatch(p -> p.targetBlock().startsWith("minecraft:stone_brick_slab[")), type);
            var section = section(roof, 13);
            // Host attachment places the eaves at the top of the ten-block mass.
            int ridgeY = 10 + height - 1;
            assertEquals(ridgeY, Collections.max(section), type);
            assertEquals(type.equals("saltbox") ? 4 : 5, section.indexOf(ridgeY), type);
        }
    }

    @Test void hostedCrossGableJoinsTwoRidgesAndSealsFourValleys() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var fixture = java.nio.file.Path.of("python_backend/eval/fixtures/plans/p2_gambrel_13.json");
        var document = mapper.readTree(fixture.toFile());
        var roofParams = (com.fasterxml.jackson.databind.node.ObjectNode) document.at("/components/1/params");
        roofParams.put("roof_type", "十字双坡顶").put("roof_block", "minecraft:stone_bricks")
                .put("wall_block", "minecraft:spruce_planks").put("roof_height", 9)
                .put("roof_slope_profile", "linear");
        var plan = mapper.treeToValue(document, LlmPlan.class);
        var patches = com.formacraft.server.compiler.ComponentPlanCompiler.compile(plan,
                new net.minecraft.util.math.BlockPos(0, 64, 0), null, null, false);
        int[][] tops = new int[13][17];
        for (var patch : patches) {
            if (patch.targetBlock() != null && patch.targetBlock().startsWith("minecraft:stone_brick")
                    && patch.dx() >= 0 && patch.dx() < 13
                    && patch.dz() >= 0 && patch.dz() < 17) {
                tops[patch.dx()][patch.dz()] = Math.max(tops[patch.dx()][patch.dz()], patch.dy());
            }
        }
        for (int x = 0; x < 13; x++) assertEquals(18, tops[x][8]);
        for (int z = 0; z < 17; z++) assertEquals(18, tops[6][z]);
        for (int x : List.of(3, 9)) for (int z : List.of(4, 12)) {
            assertEquals(14, tops[x][z]);
            assertTrue(tops[x == 3 ? 4 : 8][z] > tops[x][z]);
            assertTrue(tops[x][z == 4 ? 5 : 11] > tops[x][z]);
        }
        assertTrue(patches.stream().anyMatch(p -> p.dx() == 4 && p.dz() == 4 && p.dy() == 14
                && "minecraft:stone_bricks".equals(p.targetBlock())));
        assertTrue(patches.stream().anyMatch(p -> p.dx() == 1 && p.dz() == 5 && p.dy() == 15
                && p.targetBlock().startsWith("minecraft:stone_brick_stairs[")
                && p.targetBlock().contains("facing=south")));
        assertTrue(patches.stream().anyMatch(p -> p.dx() == 5 && p.dz() == 1 && p.dy() == 17
                && p.targetBlock().startsWith("minecraft:stone_brick_stairs[")
                && p.targetBlock().contains("facing=east")));
        assertTrue(patches.stream().anyMatch(p -> p.dx() == 3 && p.dz() == 4 && p.dy() == 14
                && "minecraft:stone_bricks".equals(p.targetBlock())));
        assertTrue(patches.stream().anyMatch(p -> p.dx() == 0 && p.dz() == 8 && p.dy() == 17
                && "minecraft:spruce_planks".equals(p.targetBlock())));
        // Check the attic above the host attachment plane and below the valley.
        assertFalse(patches.stream().anyMatch(p -> p.dx() == 3 && p.dz() == 4 && p.dy() == 12
                && BlockPatch.PLACE.equals(p.action())));
    }

    @Test void lowerCrossGableWingMeetsTheMainSlopeOnEitherAxis() {
        for (String axis : List.of("x", "z")) {
            boolean mainAlongDepth = axis.equals("z");
            var dimensions = mainAlongDepth ? new Dimensions(13, 17, 9) : new Dimensions(17, 13, 9);
            var component = new Component("ROOF", "hall", new Vec3i(0, 20, 0), dimensions, List.of(),
                    Map.of("roof_type", "cross_gable", "roof_block", "minecraft:stone_bricks",
                            "roof_slope_profile", "linear", "ridge_axis", axis,
                            "cross_gable_secondary_height", 5, "gable_walls", false));
            var patches = new RoofGenerator().generate(new SemanticComponent("ROOF", null, component));
            var tops = new HashMap<net.minecraft.util.math.BlockPos, BlockPatch>();
            for (var patch : patches) {
                int x = mainAlongDepth ? patch.dx() : patch.dz();
                int z = mainAlongDepth ? patch.dz() : patch.dx();
                var column = new net.minecraft.util.math.BlockPos(x, 0, z);
                tops.merge(column, patch, (old, next) -> old.dy() >= next.dy() ? old : next);
            }
            assertEquals(28, tops.get(new net.minecraft.util.math.BlockPos(6, 0, 0)).dy(), axis);
            var wing = tops.get(new net.minecraft.util.math.BlockPos(0, 0, 8));
            assertEquals(24, wing.dy(), axis);
            assertEquals("minecraft:stone_bricks", wing.targetBlock(), axis);
            assertEquals(24, tops.get(new net.minecraft.util.math.BlockPos(3, 0, 8)).dy(), axis);
            assertEquals(25, tops.get(new net.minecraft.util.math.BlockPos(4, 0, 8)).dy(), axis);
            var valley = tops.get(new net.minecraft.util.math.BlockPos(2, 0, 5));
            assertEquals(23, valley.dy(), axis);
            assertEquals("minecraft:stone_bricks", valley.targetBlock(), axis);
            assertEquals("minecraft:stone_brick_slab[type=bottom]",
                    tops.get(new net.minecraft.util.math.BlockPos(1, 0, 6)).targetBlock(), axis);
        }
    }

    @Test void crossGableRejectsSecondaryHeightAboveTheMainRoof() {
        var component = new Component("ROOF", "hall", new Vec3i(0, 20, 0), new Dimensions(13, 17, 9), List.of(),
                Map.of("roof_type", "cross_gable", "cross_gable_secondary_height", 10));
        var error = assertThrows(IllegalArgumentException.class,
                () -> new RoofGenerator().generate(new SemanticComponent("ROOF", null, component)));
        assertTrue(error.getMessage().contains("cross_gable_secondary_height"));
    }

    @Test void shallowCrossGableUsesSlabsAwayFromTheSolidValley() {
        var component = new Component("ROOF", "hall", new Vec3i(0, 20, 0), new Dimensions(13, 17, 5), List.of(),
                Map.of("roof_type", "cross_gable", "roof_block", "minecraft:stone_bricks",
                        "roof_slope_profile", "linear", "gable_walls", false));
        var patches = new RoofGenerator().generate(new SemanticComponent("ROOF", null, component));
        assertTrue(patches.stream().anyMatch(p -> p.dx() == 1 && p.dz() == 4 && p.dy() == 22
                && "minecraft:stone_brick_slab[type=bottom]".equals(p.targetBlock())));
        assertTrue(patches.stream().anyMatch(p -> p.dx() == 3 && p.dz() == 4 && p.dy() == 22
                && "minecraft:stone_bricks".equals(p.targetBlock())));
        assertTrue(patches.stream().anyMatch(p -> p.dx() == 6 && p.dz() == 4 && p.dy() == 24
                && "minecraft:stone_bricks".equals(p.targetBlock())));
    }

    @Test void hostedButterflyKeepsItsGutterAtTheAttachmentPlane() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var fixture = java.nio.file.Path.of("python_backend/eval/fixtures/plans/p2_gambrel_13.json");
        var document = mapper.readTree(fixture.toFile());
        var roofParams = (com.fasterxml.jackson.databind.node.ObjectNode) document.at("/components/1/params");
        roofParams.put("roof_type", "butterfly").put("roof_block", "minecraft:stone_bricks")
                .put("roof_height", 7).put("ridge_axis", "z");
        var plan = mapper.treeToValue(document, LlmPlan.class);
        var patches = com.formacraft.server.compiler.ComponentPlanCompiler.compile(plan,
                new net.minecraft.util.math.BlockPos(0, 64, 0), null, null, false);
        var roof = patches.stream().filter(p -> p.targetBlock() != null
                && p.targetBlock().startsWith("minecraft:stone_brick")).toList();
        assertEquals(List.of(16, 15, 14, 13, 12, 11, 10, 11, 12, 13, 14, 15, 16), section(roof, 13));
    }

    @Test void hostedHipInheritsTheLCutAndPreservesItsTiledValley() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var fixture = java.nio.file.Path.of("python_backend/eval/fixtures/plans/p2_gambrel_13.json");
        var document = mapper.readTree(fixture.toFile());
        var massParams = (com.fasterxml.jackson.databind.node.ObjectNode) document.at("/components/0/params");
        massParams.put("plan_type", "l_shape").put("l_cut", 5).put("l_corner", "NW");
        var roofParams = (com.fasterxml.jackson.databind.node.ObjectNode) document.at("/components/1/params");
        roofParams.put("roof_type", "hip").put("roof_block", "minecraft:stone_bricks")
                .put("roof_height", 6).put("roof_slope_profile", "linear");
        var plan = mapper.treeToValue(document, LlmPlan.class);
        var patches = com.formacraft.server.compiler.ComponentPlanCompiler.compile(plan,
                new net.minecraft.util.math.BlockPos(0, 64, 0), null, null, false);
        var roof = patches.stream().filter(p -> p.dy() >= 10 && p.targetBlock() != null
                && p.targetBlock().startsWith("minecraft:stone_brick")).toList();
        assertFalse(roof.stream().anyMatch(p -> p.dx() < 5 && p.dz() < 5));
        assertTrue(roof.stream().anyMatch(p -> p.dx() == 6 && p.dz() == 6 && p.dy() == 11
                && "minecraft:stone_bricks".equals(p.targetBlock())));
        assertTrue(roof.stream().anyMatch(p -> p.dx() == 6 && p.dz() == 7 && p.dy() == 12
                && p.targetBlock().startsWith("minecraft:stone_brick_stairs[")
                && p.targetBlock().contains("facing=south")));
    }

    @Test void authoredPlansCompileThroughTheComponentPipeline() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var manifest=mapper.readTree(java.nio.file.Path.of("python_backend/eval/fixtures/p2_roofs.json").toFile());
        int count=0;
        for(var entry:manifest) for(var fixture:entry.path("plans")) {
            var plan=mapper.readValue(java.nio.file.Path.of("python_backend/eval/fixtures/plans",fixture.asText()).toFile(),LlmPlan.class);
            var patches=com.formacraft.server.compiler.ComponentPlanCompiler.compile(plan,
                    new net.minecraft.util.math.BlockPos(0,64,0),null,null,false);
            assertFalse(patches.isEmpty(),fixture.asText());
            assertTrue(patches.stream().anyMatch(p->p.targetBlock()!=null && p.targetBlock().contains("hay_block")),fixture.asText());
            var origin=new net.minecraft.util.math.BlockPos(0,64,0);
            var min=origin.add(0,-32,-32);
            var max=origin.add(6,128,64);
            var bounds=new BuildConstraints(min,max,null,List.of());
            var expected=patches.stream().filter(p->bounds.allow(origin.add(p.dx(),p.dy(),p.dz()))).toList();
            assertFalse(expected.isEmpty());
            assertTrue(expected.size()<patches.size());
            assertEquals(expected,com.formacraft.server.patch.ServerPatchFilter.filter(
                    origin,patches,List.of(),null,true,min,max).accepted);
            var snapshot=new com.formacraft.common.tool.ToolConstraintSnapshot(
                    new com.formacraft.common.buildcontext.SelectionBox(min,max),null,List.of(),List.of(),
                    com.formacraft.common.tool.ToolConstraintSnapshot.SymmetryConstraint.none());
            assertEquals(expected,com.formacraft.common.patch.filter.PatchFilterPipeline.createDefault().apply(
                    patches,origin,new com.formacraft.common.patch.filter.PatchFilterContext(snapshot)));
            count++;
        }
        assertEquals(8,count);
    }
}
