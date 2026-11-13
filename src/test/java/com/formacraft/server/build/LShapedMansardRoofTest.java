package com.formacraft.server.build;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LShapedMansardRoofTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }

    private List<BlockPatch> roof(Map<String,Object> extras) {
        var params=new HashMap<String,Object>(Map.of("roof_type","mansard","roof_block","minecraft:stone_bricks",
                "plan_type","l_shape","l_cut",7,"l_corner","NW","overhang",1,
                "mansard_break_run",2,"mansard_break_rise",7));
        params.putAll(extras);
        var c=new Component("ROOF","hall",new Vec3i(0,20,0),new Dimensions(17,21,11),List.of(),params);
        return new RoofGenerator().generate(new SemanticComponent("ROOF",null,c));
    }

    private int top(List<BlockPatch> roof,int x,int z) {
        return roof.stream().filter(p -> p.dx()==x && p.dz()==z).mapToInt(BlockPatch::dy).max().orElseThrow();
    }

    @Test void innerAndOuterEavesStayLevelAndAllValleysJoinAtEveryCorner() {
        for(String corner:List.of("NW","NE","SW","SE")) for(int overhang:List.of(0,1,2)) {
            int w=17+2*overhang,d=21+2*overhang;
            var patches=roof(Map.of("l_corner",corner,"overhang",overhang,"double_eave",true));
            var columns=new HashMap<List<Integer>,Set<Integer>>();
            for(var p:patches) {
                int x=corner.endsWith("E") ? 16+overhang-p.dx() : p.dx()+overhang;
                int z=corner.startsWith("S") ? 20+overhang-p.dz() : p.dz()+overhang;
                assertTrue(x>=0 && x<w && z>=0 && z<d);
                columns.computeIfAbsent(List.of(x,z), k -> new HashSet<>()).add(p.dy());
            }
            for(int x=0;x<w;x++) for(int z=0;z<d;z++) {
                var ys=columns.get(List.of(x,z));
                assertEquals(x>=7 || z>=7,ys!=null);
                if(ys==null) continue;
                if(x==0 || z==0 || x==w-1 || z==d-1 || (x==7 && z<=7) || (z==7 && x<=7))
                    assertEquals(20,Collections.max(ys));
                for(int[] delta:List.of(new int[]{1,0},new int[]{0,1})) {
                    var next=columns.get(List.of(x+delta[0],z+delta[1]));
                    if(next!=null) assertFalse(Collections.disjoint(ys,next));
                }
            }
            assertEquals(27,Collections.max(columns.get(List.of(9,9))));
            assertEquals(30,patches.stream().mapToInt(BlockPatch::dy).max().orElseThrow());
            assertFalse(columns.get(List.of(w-4,d-4)).contains(20));
        }
    }

    @Test void defaultProfileReachesTheActualLWingRidgeAndImpossibleFoldsAreRejected() {
        var params=new HashMap<String,Object>(Map.of("roof_type","mansard","roof_block","minecraft:hay_block",
                "plan_type","l_shape","l_cut",7,"l_corner","NW"));
        var c=new Component("ROOF","hall",new Vec3i(0,20,0),new Dimensions(17,21,11),List.of(),params);
        var patches=new RoofGenerator().generate(new SemanticComponent("ROOF",null,c));
        assertEquals(30,patches.stream().mapToInt(BlockPatch::dy).max().orElseThrow());
        assertEquals(20,top(patches,7,7));
        assertThrows(IllegalArgumentException.class, () -> roof(Map.of("overhang",0,
                "mansard_break_run",6,"mansard_break_rise",9)));
        assertThrows(IllegalArgumentException.class, () -> roof(Map.of("overhang",0,"l_cut",16)));
    }

    @Test void automaticDormersFitCompleteFoldStripsAndStayOutsideTheCutout() {
        var plain=roof(Map.of());
        var automatic=roof(Map.of("roof_dormers",true));
        var windows=automatic.stream().filter(p -> p.dy()==28 && "minecraft:glass_pane".equals(p.targetBlock())).toList();
        assertEquals(List.of(9,14,18),windows.stream().map(BlockPatch::dz).sorted().toList());
        for(var window:windows) {
            assertEquals(0,window.dx());
            assertEquals(27,top(plain,1,window.dz()));
            assertEquals(24,top(plain,0,window.dz()));
        }
        var alongWidth=roof(Map.of("roof_dormers",true,"dormer_axis","x"));
        assertEquals(List.of(9,14),alongWidth.stream().filter(p -> p.dy()==28
                && "minecraft:glass_pane".equals(p.targetBlock())).map(BlockPatch::dx).sorted().toList());
        assertThrows(IllegalArgumentException.class, () -> roof(Map.of("mansard_dormer_count",4)));
    }

    @Test void hostedMansardInheritsTheInnerEaveAndKeepsTheAuthoredFold() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var document=mapper.readTree(java.nio.file.Path.of("python_backend/eval/fixtures/plans/p2_gambrel_13.json").toFile());
        var host=(com.fasterxml.jackson.databind.node.ObjectNode)document.at("/components/0/params");
        host.put("plan_type","l_shape").put("l_cut",5).put("l_corner","NW");
        var params=(com.fasterxml.jackson.databind.node.ObjectNode)document.at("/components/1/params");
        params.put("roof_type","mansard").put("roof_height",11).put("mansard_break_run",2).put("mansard_break_rise",7);
        var patches=com.formacraft.server.compiler.ComponentPlanCompiler.compile(mapper.treeToValue(document,LlmPlan.class),
                new net.minecraft.util.math.BlockPos(0,64,0),null,null,false);
        var roof=patches.stream().filter(p -> p.targetBlock().startsWith("minecraft:hay_block")).toList();
        assertFalse(roof.stream().anyMatch(p -> p.dx()<5 && p.dz()<5));
        assertEquals(9,top(roof,5,5));
        assertEquals(16,top(roof,7,7));
        assertEquals(19,roof.stream().mapToInt(BlockPatch::dy).max().orElseThrow());
    }
}
