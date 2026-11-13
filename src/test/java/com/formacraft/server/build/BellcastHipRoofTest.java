package com.formacraft.server.build;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.generation.component.util.RoofTypeCatalog;
import com.formacraft.common.generation.component.util.RoofTypeKind;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BellcastHipRoofTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }

    private List<BlockPatch> generate(int width, int depth, int height, Map<String,Object> extras) {
        var params = new HashMap<String,Object>(Map.of("roof_type","bellcast_hip",
                "roof_block","minecraft:hay_block"));
        params.putAll(extras);
        var component = new Component("ROOF","hall",new Vec3i(0,20,0),new Dimensions(width,depth,height),List.of(),params);
        return new RoofGenerator().generate(new SemanticComponent("ROOF",null,component));
    }

    private int top(List<BlockPatch> roof, int x, int z) {
        return roof.stream().filter(p -> p.dx()==x && p.dz()==z).mapToInt(BlockPatch::dy).max().orElseThrow();
    }

    @Test void allFourEavesRiseToSymmetricRidgesAtOddAndEvenScales() {
        for (int[] size : List.of(new int[]{13,17},new int[]{14,18},new int[]{13,13},new int[]{14,14})) {
            int w=size[0], d=size[1];
            var roof=generate(w,d,13,Map.of());
            assertEquals(w*d,roof.stream().map(p -> List.of(p.dx(),p.dz())).distinct().count());
            for (int x=0;x<w;x++) for (int z=0;z<d;z++) {
                assertEquals(top(roof,x,z),top(roof,w-1-x,d-1-z));
                if (x==0 || x==w-1 || z==0 || z==d-1) assertEquals(20,top(roof,x,z));
            }
            assertEquals(32,top(roof,w/2,d/2));
            assertFalse(roof.stream().anyMatch(p -> p.dx()==w/2 && p.dz()==d/2 && p.dy()==20));
        }
        var roof=generate(13,17,13,Map.of());
        assertEquals(List.of(20,21,22,25,27,30,32,30,27,25,22,21,20),
                java.util.stream.IntStream.range(0,13).mapToObj(x -> top(roof,x,8)).toList());
        for (int z=6;z<=10;z++) assertEquals(32,top(roof,6,z));
    }

    @Test void tilesFaceUphillAndHipJunctionsStaySolid() {
        var roof=generate(13,17,7,Map.of("roof_block","minecraft:stone_bricks"));
        assertTrue(roof.stream().anyMatch(p -> p.dx()==0 && p.dz()==8
                && p.targetBlock().startsWith("minecraft:stone_brick_slab[")));
        for (var sample : List.of(new Object[]{2,8,"east"},new Object[]{10,8,"west"},
                new Object[]{6,2,"south"},new Object[]{6,14,"north"})) {
            int x=(int)sample[0],z=(int)sample[1];
            assertTrue(roof.stream().anyMatch(p -> p.dx()==x && p.dz()==z && p.dy()==21
                    && p.targetBlock().startsWith("minecraft:stone_brick_stairs[")
                    && p.targetBlock().contains("facing="+sample[2])));
        }
        assertTrue(roof.stream().anyMatch(p -> p.dx()==2 && p.dz()==2 && p.dy()==21
                && "minecraft:stone_bricks".equals(p.targetBlock())));
    }

    @Test void overhangAndCustomFoldKeepEveryNeighborJoined() {
        var roof=generate(13,17,17,Map.of("overhang",2,"double_eave",true,"bellcast_break_run_fraction",0.5,
                "bellcast_break_rise_fraction",0.2));
        assertEquals(-2,roof.stream().mapToInt(BlockPatch::dx).min().orElseThrow());
        assertEquals(14,roof.stream().mapToInt(BlockPatch::dx).max().orElseThrow());
        assertEquals(18,roof.stream().mapToInt(BlockPatch::dz).max().orElseThrow());
        assertEquals(23,top(roof,2,8));
        assertEquals(36,top(roof,6,8));
        var columns=new HashMap<List<Integer>,Set<Integer>>();
        for (var p:roof) columns.computeIfAbsent(List.of(p.dx(),p.dz()), k -> new HashSet<>()).add(p.dy());
        for (var entry:columns.entrySet()) for (int[] delta:List.of(new int[]{1,0},new int[]{0,1})) {
            var next=columns.get(List.of(entry.getKey().get(0)+delta[0],entry.getKey().get(1)+delta[1]));
            if (next!=null) assertFalse(Collections.disjoint(entry.getValue(),next));
        }
        assertThrows(IllegalArgumentException.class, () -> generate(13,17,7,
                Map.of("bellcast_break_run_fraction",0.2,"bellcast_break_rise_fraction",0.4)));
    }

    @Test void hostedChineseNameCompilesAsFourSlopes() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var document=mapper.readTree(java.nio.file.Path.of("python_backend/eval/fixtures/plans/p2_gambrel_13.json").toFile());
        var params=(com.fasterxml.jackson.databind.node.ObjectNode)document.at("/components/1/params");
        params.put("roof_type","钟形四坡顶").put("roof_height",13);
        var plan=mapper.treeToValue(document,LlmPlan.class);
        var patches=com.formacraft.server.compiler.ComponentPlanCompiler.compile(plan,
                new net.minecraft.util.math.BlockPos(0,64,0),null,null,false);
        var roof=patches.stream().filter(p -> "minecraft:hay_block".equals(p.targetBlock())).toList();
        assertEquals(9,top(roof,6,0));
        assertEquals(21,top(roof,6,8));
        for (String alias:List.of("bellcast_hip","bellcast hip","钟形四坡顶"))
            assertEquals(RoofTypeKind.BELLCAST_HIP,RoofTypeCatalog.parseExplicit(alias).orElseThrow());
    }
}
