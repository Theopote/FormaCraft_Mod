package com.formacraft.server.build;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MansardSurfaceTilingTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }

    private List<BlockPatch> roof(int height,Map<String,Object> extras) {
        var params=new HashMap<String,Object>(Map.of("roof_type","mansard","roof_block","minecraft:stone_bricks",
                "mansard_break_run",2,"mansard_break_rise",7));
        params.putAll(extras);
        var c=new Component("ROOF","hall",new Vec3i(0,20,0),new Dimensions(17,21,height),List.of(),params);
        return new RoofGenerator().generate(new SemanticComponent("ROOF",null,c));
    }

    private BlockPatch top(List<BlockPatch> roof,int x,int z) {
        return roof.stream().filter(p -> p.dx()==x && p.dz()==z).max(Comparator.comparingInt(BlockPatch::dy)).orElseThrow();
    }

    @Test void upperStairsFaceTheRidgeWhileSteepLowerSlopesAndJunctionsStaySolid() {
        var patches=roof(13,Map.of());
        for(var sample:List.of(new Object[]{3,10,"east"},new Object[]{13,10,"west"},
                new Object[]{8,3,"south"},new Object[]{8,17,"north"})) {
            var surface=top(patches,(int)sample[0],(int)sample[1]);
            assertEquals(28,surface.dy());
            assertTrue(surface.targetBlock().startsWith("minecraft:stone_brick_stairs["));
            assertTrue(surface.targetBlock().contains("facing="+sample[2]));
        }
        assertEquals("minecraft:stone_bricks",top(patches,1,10).targetBlock());
        assertEquals("minecraft:stone_bricks",top(patches,3,3).targetBlock());
        assertEquals("minecraft:stone_bricks",top(patches,8,10).targetBlock());
        assertEquals(32,top(patches,8,10).dy());
        for(int y=20;y<24;y++) {
            int level=y;
            assertTrue(patches.stream().anyMatch(p -> p.dx()==1 && p.dz()==10 && p.dy()==level));
        }
        assertFalse(patches.stream().anyMatch(p -> p.dx()==8 && p.dz()==10 && p.dy()==20));
    }

    @Test void shallowUpperSlopesUseSlabsEvenWhenQuantizationJumpsOneBlock() {
        var rectangle=roof(11,Map.of());
        assertEquals(27,top(rectangle,2,10).dy());
        assertEquals(28,top(rectangle,3,10).dy());
        assertEquals("minecraft:stone_brick_slab[type=bottom]",top(rectangle,2,10).targetBlock());
        assertEquals("minecraft:stone_brick_slab[type=bottom]",top(rectangle,3,10).targetBlock());
        var lShape=roof(11,Map.of("plan_type","l_shape","l_cut",7,"l_corner","NW","overhang",1));
        assertEquals(27,top(lShape,7,8).dy());
        assertEquals(28,top(lShape,7,9).dy());
        assertEquals("minecraft:stone_brick_slab[type=bottom]",top(lShape,7,8).targetBlock());
        assertEquals("minecraft:stone_bricks",top(lShape,9,9).targetBlock());
    }

    @Test void materialWithoutTileVariantsKeepsItsProfileAndAllRisersJoined() {
        var patches=roof(11,Map.of("roof_block","minecraft:hay_block"));
        assertTrue(patches.stream().allMatch(p -> "minecraft:hay_block".equals(p.targetBlock())));
        assertEquals(List.of(20,24,27,28,28,29,29,30,30,30,29,29,28,28,27,24,20),
                java.util.stream.IntStream.range(0,17).mapToObj(x -> top(patches,x,10).dy()).toList());
        var columns=new HashMap<List<Integer>,Set<Integer>>();
        for(var p:patches) columns.computeIfAbsent(List.of(p.dx(),p.dz()),k -> new HashSet<>()).add(p.dy());
        for(var entry:columns.entrySet()) for(int[] delta:List.of(new int[]{1,0},new int[]{0,1})) {
            var next=columns.get(List.of(entry.getKey().get(0)+delta[0],entry.getKey().get(1)+delta[1]));
            if(next!=null) assertFalse(Collections.disjoint(entry.getValue(),next));
        }
    }
}
