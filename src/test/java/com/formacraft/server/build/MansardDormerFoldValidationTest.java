package com.formacraft.server.build;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.generation.component.util.ComponentRoofSpecialtyDecorator;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MansardDormerFoldValidationTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }

    private List<BlockPatch> narrowWing(String face,Integer count,boolean enabled) {
        var params=new HashMap<String,Object>(Map.of("roof_type","mansard","roof_block","minecraft:stone_bricks",
                "plan_type","l_shape","l_cut",23,"l_corner","NW","roof_height",4,
                "mansard_break_run",12,"mansard_break_rise",2,"roof_dormers",enabled));
        if(face!=null) params.put("dormer_face",face);
        if(count!=null) params.put("mansard_dormer_count",count);
        var c=new Component("ROOF","hall",new Vec3i(-13,37,7),new Dimensions(41,71,4),List.of(),params);
        return new RoofGenerator().generate(new SemanticComponent("ROOF",null,c));
    }

    @Test void aLowerPlateauOnANarrowWingCannotMasqueradeAsTheAuthoredFold() {
        var shell=narrowWing("north",1,false);
        assertEquals(38,shell.stream().filter(p -> p.dx()==13 && p.dz()==19)
                .mapToInt(BlockPatch::dy).max().orElseThrow());
        // The authored fold is y=37+2=39; this northern wing never reaches it.
        for(Integer count:Arrays.asList(1,null)) {
            var error=assertThrows(IllegalArgumentException.class,() -> narrowWing("north",count,true));
            assertTrue(error.getMessage().contains("capacity of 0"));
        }
    }

    @Test void oppositeFaceAndAutomaticAxisStillFindTheRealFoldAtAnOffsetOrigin() {
        var south=narrowWing("south",1,true).stream().filter(p -> p.dy()==40
                && p.targetBlock().startsWith("minecraft:glass_pane")).toList();
        assertEquals(1,south.size());
        assertEquals(66,south.getFirst().dz());
        var automatic=narrowWing(null,null,true).stream().filter(p -> p.dy()==40
                && p.targetBlock().startsWith("minecraft:glass_pane")).toList();
        assertEquals(3,automatic.size());
        assertTrue(automatic.stream().allMatch(p -> p.dx()==-2));
    }

    private List<BlockPatch> strip(int faceY,int frontY,boolean coverFace) {
        var roof=new ArrayList<BlockPatch>();
        for(int z=4;z<=10;z++) {
            roof.add(new BlockPatch(BlockPatch.PLACE,2,faceY,z,"minecraft:stone_bricks"));
            roof.add(new BlockPatch(BlockPatch.PLACE,1,frontY,z,"minecraft:stone_bricks"));
            if(coverFace) roof.add(new BlockPatch(BlockPatch.PLACE,2,faceY+1,z,"minecraft:stone_bricks"));
        }
        return roof;
    }

    @Test void fitterChecksBothSurfaceHeightsAndDoesNotMistakeRiserFillForTheRoofSkin() {
        var profile=ComponentRoofSpecialtyDecorator.resolveMansardProfile(17,21,11,
                Map.of("mansard_break_run",2,"mansard_break_rise",7));
        for(var invalid:List.of(strip(26,24,false),strip(27,23,false),strip(27,24,true))) {
            var out=new ArrayList<BlockPatch>();
            assertThrows(IllegalArgumentException.class,() -> ComponentRoofSpecialtyDecorator.emitDormers(out,invalid,
                    0,20,0,17,21,profile,1,Map.of("dormer_axis","z"),
                    "minecraft:stone_bricks","minecraft:glass_pane","minecraft:stone_bricks"));
            assertTrue(out.isEmpty());
        }
        var out=new ArrayList<BlockPatch>();
        ComponentRoofSpecialtyDecorator.emitDormers(out,strip(27,24,false),0,20,0,17,21,profile,1,
                Map.of("dormer_axis","z"),"minecraft:stone_bricks","minecraft:glass_pane","minecraft:stone_bricks");
        assertTrue(out.stream().anyMatch(p -> p.dy()==28 && p.targetBlock().equals("minecraft:glass_pane")));
    }
}
