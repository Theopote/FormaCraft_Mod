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

    @Test void allSpecialtyRoofsExecuteAtTwoScalesWithoutFillingInterior() {
        for (String type : List.of("gambrel","barrel_vault","onion_dome","steep_thatch")) {
            for (int span : List.of(13,21)) {
                int height=span;
                var patches=new RoofGenerator().generate(new SemanticComponent("ROOF",null,roof(type,span,height)));
                assertFalse(patches.isEmpty(),type);
                assertEquals(20+height-1,patches.stream().mapToInt(p->p.dy()).max().orElseThrow(),type);
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
        int neck=onion.stream().filter(p->p.dy()==0).mapToInt(p->p.dx()).max().orElseThrow();
        assertTrue(onion.stream().filter(p->p.dy()>0).mapToInt(p->p.dx()).max().orElseThrow()>neck);
    }

    @Test void steepThatchRejectsShallowGeometryAndAliasesRemainExecutable() {
        assertThrows(IllegalArgumentException.class,()->SpecialtyRoofSurfaces.generate(
                RoofTypeKind.STEEP_THATCH,0,0,0,21,25,4,"minecraft:hay_block",null,0));
        for(String alias:List.of("折线双坡顶","筒拱屋顶","洋葱穹顶","陡坡茅草顶"))
            assertTrue(RoofTypeCatalog.parseExplicit(alias).isPresent(),alias);
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
