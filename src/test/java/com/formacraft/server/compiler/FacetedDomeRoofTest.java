package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.generation.component.util.*;
import com.formacraft.common.llm.dto.*;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FacetedDomeRoofTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    private Component roof(String type,int segments) {
        return new Component("ROOF","tower",new Vec3i(0,20,0),new Dimensions(17,17,7),List.of(),
                Map.of("roof_type",type,"segments",segments,"roof_block","minecraft:black_concrete"));
    }
    @Test void acceptedDomeNamesGenerateRaisedHollowCaps() {
        for(var type:List.of("polyhedral_dome","faceted_dome","polygonal_dome")) {
            var c=roof(type,8);
            assertEquals(RoofTypeKind.DOME,RoofTypeCatalog.parseExplicit(type).orElseThrow());
            assertTrue(RoofDesignValidator.check(LlmPlanTestFixtures.builder().components(List.of(c)).build(),List.of(c)).isEmpty());
            var patches=new RoofGenerator().generate(new SemanticComponent("ROOF",null,c));
            assertEquals(26,patches.stream().mapToInt(p -> p.dy()).max().orElseThrow());
            assertTrue(patches.stream().anyMatch(p -> p.dx()==8 && p.dz()==8 && p.dy()==26));
            assertFalse(patches.stream().anyMatch(p -> p.dx()==8 && p.dz()==8 && p.dy()==20));
        }
    }
    @Test void explicitFacetCountChangesGeometry() {
        var generator=new RoofGenerator();
        var four=generator.generate(new SemanticComponent("ROOF",null,roof("polyhedral_dome",4)));
        var eight=generator.generate(new SemanticComponent("ROOF",null,roof("polyhedral_dome",8)));
        assertNotEquals(new HashSet<>(four),new HashSet<>(eight));
    }

    @Test void roundAndFacetedDrumsFollowTheCapPerimeterAndKeepTheInteriorHollow() {
        for (String type : List.of("dome", "faceted_dome")) {
            var component = new Component("ROOF", "tower", new Vec3i(0, 20, 0), new Dimensions(17, 17, 9),
                    List.of(), Map.of("roof_type", type, "roof_block", "minecraft:black_concrete",
                            "dome_drum_height", 3, "dome_drum_block", "stone_bricks"));
            var patches = new RoofGenerator().generate(new SemanticComponent("ROOF", null, component));
            assertEquals(28, patches.stream().mapToInt(p -> p.dy()).max().orElseThrow(), type);
            var cap = new HashSet<BlockPos>();
            var drum = new HashSet<BlockPos>();
            for (var patch : patches) {
                if (patch.dy() >= 23) cap.add(new BlockPos(patch.dx(), 0, patch.dz()));
                if ("minecraft:stone_bricks".equals(patch.targetBlock())) {
                    drum.add(new BlockPos(patch.dx(), patch.dy(), patch.dz()));
                }
            }
            assertFalse(drum.isEmpty(), type);
            assertFalse(drum.contains(new BlockPos(8, 20, 8)), type);
            for (BlockPos column : cap) {
                boolean boundary = !cap.contains(column.north()) || !cap.contains(column.south())
                        || !cap.contains(column.east()) || !cap.contains(column.west());
                for (int y = 20; y <= 22; y++) {
                    assertEquals(boundary, drum.contains(column.up(y)), type + " " + column);
                }
                if (boundary) {
                    assertTrue(patches.stream().anyMatch(p -> p.dx() == column.getX() && p.dz() == column.getZ()
                            && p.dy() == 23 && "minecraft:black_concrete".equals(p.targetBlock())));
                }
            }
        }
    }

    @Test void hostedDomeKeepsTheDrumOnTheAttachmentPlane() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var fixture = java.nio.file.Path.of("python_backend/eval/fixtures/plans/p2_gambrel_13.json");
        var document = mapper.readTree(fixture.toFile());
        var params = (com.fasterxml.jackson.databind.node.ObjectNode) document.at("/components/1/params");
        params.put("roof_type", "dome").put("roof_block", "minecraft:black_concrete")
                .put("roof_height", 9).put("dome_drum_height", 3).put("wall_block", "minecraft:stone_bricks")
                .put("dome_rib_count", 4).put("dome_rib_block", "minecraft:quartz_block");
        var plan = mapper.treeToValue(document, LlmPlan.class);
        var patches = ComponentPlanCompiler.compile(plan, new BlockPos(0, 64, 0), null, null, false);
        assertTrue(patches.stream().anyMatch(p -> p.dx() == 0 && p.dz() == 8 && p.dy() == 10
                && "minecraft:stone_bricks".equals(p.targetBlock())));
        assertTrue(patches.stream().anyMatch(p -> p.dx() == 6 && p.dz() == 8 && p.dy() == 18
                && "minecraft:black_concrete".equals(p.targetBlock())));
        assertTrue(patches.stream().anyMatch(p -> p.dx() == 9 && p.dz() == 8 && p.dy() > 13
                && "minecraft:quartz_block".equals(p.targetBlock())));
        assertFalse(patches.stream().anyMatch(p -> p.dx() == 6 && p.dz() == 8 && p.dy() == 11
                && "minecraft:stone_bricks".equals(p.targetBlock())));

        params.put("dome_oculus_radius", 2);
        var openPlan = mapper.treeToValue(document, LlmPlan.class);
        var openPatches = ComponentPlanCompiler.compile(openPlan, new BlockPos(0, 64, 0), null, null, false);
        assertFalse(openPatches.stream().anyMatch(p -> p.dy() > 10
                && Math.pow(p.dx() - 6, 2) + Math.pow(p.dz() - 8, 2) <= 4));
        assertTrue(openPatches.stream().anyMatch(p -> p.dx() == 9 && p.dz() == 8 && p.dy() > 13
                && "minecraft:quartz_block".equals(p.targetBlock())));
    }

    @Test void drumCannotConsumeTheCapHeightBudget() {
        for (int drum : List.of(-1, 6)) {
            var component = new Component("ROOF", "tower", new Vec3i(0, 20, 0), new Dimensions(17, 17, 7),
                    List.of(), Map.of("roof_type", "dome", "dome_drum_height", drum));
            var error = assertThrows(IllegalArgumentException.class,
                    () -> new RoofGenerator().generate(new SemanticComponent("ROOF", null, component)));
            assertTrue(error.getMessage().contains("dome_drum_height"));
        }
    }

    @Test void domeRibsFollowTheCapWithoutChangingItsEnvelopeOrDrum() {
        for (String type : List.of("dome", "faceted_dome")) {
            Set<BlockPos> base = new HashSet<>();
            for (int count : List.of(0, 1, 4, 8)) {
                var component = new Component("ROOF", "tower", new Vec3i(0, 20, 0), new Dimensions(17, 17, 9),
                        List.of(), Map.of("roof_type", type, "roof_block", "minecraft:black_concrete",
                                "dome_drum_height", 3, "dome_drum_block", "stone_bricks",
                                "dome_rib_count", count, "dome_rib_block", "quartz_block"));
                var patches = new RoofGenerator().generate(new SemanticComponent("ROOF", null, component));
                var cells = new HashSet<BlockPos>();
                for (var patch : patches) cells.add(new BlockPos(patch.dx(), patch.dy(), patch.dz()));
                if (count == 0) base.addAll(cells);
                else assertEquals(base, cells, type);
                var ribs = patches.stream().filter(p -> "minecraft:quartz_block".equals(p.targetBlock())).toList();
                assertEquals(count > 0, ribs.stream().anyMatch(p -> p.dx() == 12 && p.dz() == 8), type);
                assertEquals(count >= 4, ribs.stream().anyMatch(p -> p.dx() == 4 && p.dz() == 8), type);
                assertEquals(count == 8, ribs.stream().anyMatch(p -> p.dx() == 12 && p.dz() == 12), type);
                assertTrue(ribs.stream().allMatch(p -> p.dy() >= 23 && (p.dx() != 8 || p.dz() != 8)), type);
            }
        }
    }

    @Test void oculusCutsTheCapWithoutAddingAnInnerDrumWall() {
        for (String type : List.of("dome", "faceted_dome")) {
            for (int span : List.of(16, 17)) {
                var generator = new RoofGenerator();
                var params = new HashMap<String, Object>(Map.of("roof_type", type,
                        "roof_block", "minecraft:black_concrete", "dome_drum_height", 3,
                        "dome_drum_block", "stone_bricks", "dome_rib_count", 8, "dome_rib_block", "quartz_block"));
                var closed = new Component("ROOF", "tower", new Vec3i(0, 20, 0),
                        new Dimensions(span, span, 9), List.of(), Map.copyOf(params));
                var closedPatches = generator.generate(new SemanticComponent("ROOF", null, closed));
                params.put("dome_oculus_radius", 2);
                var open = new Component("ROOF", "tower", closed.relativePosition(), closed.dimensions(), List.of(), params);
                var openPatches = generator.generate(new SemanticComponent("ROOF", null, open));
                double center = (span - 1) / 2.0;
                var expected = new HashSet<BlockPos>();
                var actual = new HashSet<BlockPos>();
                var closedDrum = new HashSet<BlockPos>();
                var openDrum = new HashSet<BlockPos>();
                for (var patch : closedPatches) {
                    if (patch.dy() < 23) closedDrum.add(new BlockPos(patch.dx(), patch.dy(), patch.dz()));
                    else if (Math.pow(patch.dx() - center, 2) + Math.pow(patch.dz() - center, 2) > 4) {
                        expected.add(new BlockPos(patch.dx(), 0, patch.dz()));
                    }
                }
                for (var patch : openPatches) {
                    if (patch.dy() < 23) openDrum.add(new BlockPos(patch.dx(), patch.dy(), patch.dz()));
                    else actual.add(new BlockPos(patch.dx(), 0, patch.dz()));
                }
                assertEquals(expected, actual, type);
                assertEquals(closedDrum, openDrum, type);
            }
        }
    }

    @Test void oculusCannotBreakTheOuterRim() {
        var component = new Component("ROOF", "tower", new Vec3i(0, 20, 0), new Dimensions(17, 17, 9), List.of(),
                Map.of("roof_type", "faceted_dome", "dome_segments", 4, "dome_oculus_radius", 7));
        var error = assertThrows(IllegalArgumentException.class,
                () -> new RoofGenerator().generate(new SemanticComponent("ROOF", null, component)));
        assertTrue(error.getMessage().contains("dome_oculus_radius"));
    }
}
