package com.formacraft.server.compiler;

import com.formacraft.common.build.*;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.typology.TypologyPatchBridge;
import com.formacraft.server.generation.typology.builder.RadialTerraceHallBuilder;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LandmarkCompatibilityTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }

    @Test void loggedTempleComposesInternalTrimBeforeComponentConflictValidation() throws Exception {
        var plan=LlmPlanParser.parse(Files.readString(Path.of("python_backend/tests/fixtures/complex_build_retest/oct18_landmark_4.normalized.json")));
        var owner=plan.components().getFirst();
        var structure=RadialTerraceHallBuilder.generate(owner.params(),BlockPos.ORIGIN,null,null);
        var patches=TypologyPatchBridge.toBlockPatches(structure,BlockPos.ORIGIN);
        assertTrue(patches.size()>2000);
        assertEquals(patches.size(),patches.stream().map(p->new BlockPos(p.dx(),p.dy(),p.dz())).distinct().count());
        assertTrue(patches.stream().anyMatch(p->p.targetBlock().startsWith("minecraft:quartz_stairs[")));
        var ledger=new PatchOccupancyIndex();
        assertTrue(ledger.accept(owner,patches).isEmpty());
        var foreign=new Component("MASS_MAIN","foreign",null,null,List.of(),Map.of("component_id","foreign"));
        assertTrue(ledger.accept(foreign,List.of(new com.formacraft.common.patch.BlockPatch(com.formacraft.common.patch.BlockPatch.PLACE,0,0,0,"minecraft:diamond_block"))).isPresent());
    }

    @Test void lastAirAndFullBlockStateSurviveInternalComposition() {
        var origin=new BlockPos(100,64,-20);
        var structure=new GeneratedStructure(null,origin,"composed",List.of(
                new PlannedBlock(origin,Blocks.STONE.getDefaultState()),
                new PlannedBlock(origin,Blocks.AIR.getDefaultState()),
                new PlannedBlock(origin.east(),Blocks.STONE.getDefaultState()),
                new PlannedBlock(origin.east(),Blocks.QUARTZ_STAIRS.getDefaultState())));
        var patches=TypologyPatchBridge.toBlockPatches(structure,origin);
        assertEquals(2,patches.size());
        assertEquals(com.formacraft.common.patch.BlockPatch.REMOVE,patches.getFirst().action());
        assertTrue(patches.getLast().targetBlock().contains("facing=north"));
    }

    @Test void sphereIsVolumetricAndHollowRatherThanCircularExtrusion() throws Exception {
        var plan=LlmPlanParser.parse(Files.readString(Path.of("python_backend/tests/fixtures/complex_build_retest/oct18_landmark_6.normalized.json")));
        var c=plan.components().stream().filter(v->"op_sphere_lower".equals(v.params().get("component_id"))).findFirst().orElseThrow();
        var patches=new com.formacraft.common.generation.component.impl.MassMainGenerator().generate(
                new com.formacraft.common.compiler.semantic.SemanticComponent(c.componentType(),null,c));
        assertFalse(patches.isEmpty());
        long middle=patches.stream().filter(p->p.dy()==45).count();
        long bottom=patches.stream().filter(p->p.dy()==34).count();
        assertNotEquals(middle,bottom,"Sphere cross-sections must vary with height");
        assertFalse(patches.stream().anyMatch(p->p.dx()==0 && p.dy()==45 && p.dz()==0),"Hollow sphere center stays empty");
        assertTrue(patches.stream().allMatch(p->p.targetBlock().equals("minecraft:pink_terracotta")));
    }

    @Test void enclosureWallLeavesCourtyardEmptyAndCutsAllAuthoredGateSides() {
        for(String side:List.of("north","south","east","west")) {
            var wall=new Component("WALL","s",new Vec3i(0,0,0),new Dimensions(21,21,8),List.of(),
                    Map.of("wall_type","curtain_wall","thickness",3,"anchor_mode","center",
                            "gate_opening",Map.of("side",side,"width",5,"height",4)));
            var patches=new com.formacraft.common.generation.component.impl.WallComponentGenerator().generate(
                    new com.formacraft.common.compiler.semantic.SemanticComponent("WALL",null,wall));
            assertFalse(patches.isEmpty());
            assertTrue(patches.stream().allMatch(p->Math.abs(p.dx())>=8 || Math.abs(p.dz())>=8));
            int x=side.equals("east")?10:side.equals("west")?-10:0;
            int z=side.equals("south")?10:side.equals("north")?-10:0;
            assertFalse(patches.stream().anyMatch(p->p.dx()==x && p.dz()==z && p.dy()<4));
            assertTrue(patches.stream().anyMatch(p->p.dx()==x && p.dz()==z && p.dy()==4));
        }
    }

    @Test void volumeJoinDoesNotGrantRectangularOrMostlyOverlappingMassesOverwrite() {
        var index=new PatchOccupancyIndex();
        var base=new Component("MASS_MAIN","s",new Vec3i(0,0,0),new Dimensions(5,5,5),List.of(),Map.of("component_id","base"));
        var first=new com.formacraft.common.patch.BlockPatch(com.formacraft.common.patch.BlockPatch.PLACE,0,0,0,"minecraft:stone");
        assertTrue(index.accept(base,List.of(first)).isEmpty());
        var sculpt=new Component("MASS_SECONDARY","s",new Vec3i(0,0,0),new Dimensions(5,5,5),List.of(),Map.of("volume_geometry","sphere"));
        var patches=new ArrayList<com.formacraft.common.patch.BlockPatch>();
        for(int x=0;x<3;x++) patches.add(new com.formacraft.common.patch.BlockPatch(com.formacraft.common.patch.BlockPatch.PLACE,x,0,0,"minecraft:quartz_block"));
        var rectangle=new Component("MASS_SECONDARY","s",sculpt.relativePosition(),sculpt.dimensions(),List.of(),Map.of("shape","rectangle"));
        assertEquals(0,index.trimCompoundMassJoin(rectangle,new ArrayList<>(patches)));
        assertEquals(0,index.trimCompoundMassJoin(sculpt,new ArrayList<>(List.of(patches.getFirst()))));
        assertEquals(1,index.trimCompoundMassJoin(sculpt,patches));
        assertEquals("minecraft:stone",index.placeAt(BlockPos.ORIGIN).targetBlock());
        assertTrue(index.accept(sculpt,patches).isEmpty());
    }
}
