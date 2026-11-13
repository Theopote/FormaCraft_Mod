package com.formacraft.server.build;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MansardDormerFacingTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }

    private List<BlockPatch> roof(String face,Map<String,Object> extras,Vec3i origin) {
        var params=new HashMap<String,Object>(Map.of("roof_type","mansard","roof_block","minecraft:stone_bricks",
                "wall_block","minecraft:stone_bricks","mansard_break_run",2,"mansard_break_rise",7,"mansard_dormer_count",2,"dormer_face",face));
        params.putAll(extras);
        var c=new Component("ROOF","hall",origin,new Dimensions(17,21,11),List.of(),params);
        return new RoofGenerator().generate(new SemanticComponent("ROOF",null,c));
    }

    private List<BlockPatch> windows(List<BlockPatch> roof) {
        return roof.stream().filter(p -> p.dy()==28 && p.targetBlock().startsWith("minecraft:glass_pane")).toList();
    }

    @Test void allFourFacesPlaceWindowsOnTheRequestedSideAndPointLintelsInward() {
        var origin=new Vec3i(-11,20,7);
        for(String face:List.of("north","south","west","east")) {
            var roof=roof(face,Map.of(),origin);
            var panes=windows(roof);
            assertEquals(2,panes.size(),face);
            String inward=switch(face) {case "north" -> "south";case "south" -> "north";case "west" -> "east";default -> "west";};
            var roofStairs=roof.stream().filter(p -> p.dy()>30
                    && p.targetBlock().startsWith("minecraft:stone_brick_stairs[")).toList();
            assertFalse(roofStairs.isEmpty(),face);
            assertTrue(roofStairs.stream().allMatch(p -> p.targetBlock().contains("facing="+inward)),face);
            for(var pane:panes) {
                switch(face) {
                    case "north" -> assertEquals(origin.z()+1,pane.dz());
                    case "south" -> assertEquals(origin.z()+19,pane.dz());
                    case "west" -> assertEquals(origin.x()+1,pane.dx());
                    case "east" -> assertEquals(origin.x()+15,pane.dx());
                }
                assertTrue(roof.stream().anyMatch(p -> p.dx()==pane.dx() && p.dz()==pane.dz() && p.dy()==30
                        && p.targetBlock().startsWith("minecraft:stone_brick_stairs[")
                        && p.targetBlock().contains("facing="+inward)),face);
            }
        }
    }

    private Set<List<Integer>> openings(List<BlockPatch> patches,boolean mirrorZ) {
        var result=new HashSet<List<Integer>>();
        for(var p:patches) if(BlockPatch.REMOVE.equals(p.action()))
            result.add(List.of(mirrorZ ? p.dx() : 16-p.dx(),p.dy(),mirrorZ ? 20-p.dz() : p.dz()));
        return result;
    }

    @Test void oppositeFacesMirrorTheCarvedPocketsAsWellAsTheWindowShells() {
        var origin=new Vec3i(0,20,0);
        var north=roof("north",Map.of(),origin);
        var south=roof("south",Map.of(),origin);
        var west=roof("west",Map.of(),origin);
        var east=roof("east",Map.of(),origin);
        var southHoles=new HashSet<List<Integer>>();
        var eastHoles=new HashSet<List<Integer>>();
        for(var p:south) if(BlockPatch.REMOVE.equals(p.action())) southHoles.add(List.of(p.dx(),p.dy(),p.dz()));
        for(var p:east) if(BlockPatch.REMOVE.equals(p.action())) eastHoles.add(List.of(p.dx(),p.dy(),p.dz()));
        assertFalse(southHoles.isEmpty());
        assertEquals(openings(north,true),southHoles);
        assertEquals(openings(west,false),eastHoles);
    }

    @Test void lShapedAutomaticCapacityComesFromTheSelectedFace() {
        var params=new HashMap<String,Object>(Map.of("roof_type","mansard","roof_block","minecraft:stone_bricks",
                "plan_type","l_shape","l_cut",7,"l_corner","NW","overhang",1,
                "mansard_break_run",2,"mansard_break_rise",7,"roof_dormers",true));
        for(String face:List.of("north","south","east")) {
            params.put("dormer_face",face);
            var c=new Component("ROOF","hall",new Vec3i(0,20,0),new Dimensions(17,21,11),List.of(),params);
            var panes=windows(new RoofGenerator().generate(new SemanticComponent("ROOF",null,c)));
            assertEquals(face.equals("north") ? 2 : 3,panes.size(),face);
            for(var pane:panes) {
                if(face.equals("north")) assertEquals(0,pane.dz());
                if(face.equals("south")) assertEquals(20,pane.dz());
                if(face.equals("east")) assertEquals(16,pane.dx());
                assertFalse(pane.dx()<6 && pane.dz()<6);
            }
        }
    }

    @Test void conflictingAxisAndInvalidFaceReportGeometryErrors() {
        var origin=new Vec3i(0,20,0);
        assertThrows(IllegalArgumentException.class, () -> roof("south",Map.of("dormer_axis","z"),origin));
        assertThrows(IllegalArgumentException.class, () -> roof("east",Map.of("dormer_axis","x"),origin));
        assertThrows(IllegalArgumentException.class, () -> roof("up",Map.of(),origin));
        assertEquals(2,windows(roof("south",Map.of("dormer_axis","x"),origin)).size());
    }
}
