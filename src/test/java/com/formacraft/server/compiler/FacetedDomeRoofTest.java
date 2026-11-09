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
                .put("roof_height", 9).put("dome_drum_height", 3).put("wall_block", "minecraft:stone_bricks");
        var plan = mapper.treeToValue(document, LlmPlan.class);
        var patches = ComponentPlanCompiler.compile(plan, new BlockPos(0, 64, 0), null, null, false);
        assertTrue(patches.stream().anyMatch(p -> p.dx() == 0 && p.dz() == 8 && p.dy() == 10
                && "minecraft:stone_bricks".equals(p.targetBlock())));
        assertTrue(patches.stream().anyMatch(p -> p.dx() == 6 && p.dz() == 8 && p.dy() == 18
                && "minecraft:black_concrete".equals(p.targetBlock())));
        assertFalse(patches.stream().anyMatch(p -> p.dx() == 6 && p.dz() == 8 && p.dy() == 11
                && "minecraft:stone_bricks".equals(p.targetBlock())));
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
}
