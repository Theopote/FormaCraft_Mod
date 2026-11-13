package com.formacraft.server.build;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.generation.component.util.ComponentRoofSpecialtyDecorator;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MansardAutomaticDormerFitTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }

    private List<BlockPatch> roof(int width,int depth,int fold,int rise,Map<String,Object> extras) {
        var params=new HashMap<String,Object>(Map.of("roof_type","mansard","roof_block","minecraft:stone_bricks",
                "mansard_break_run",fold,"mansard_break_rise",rise,"roof_dormers",true));
        params.putAll(extras);
        var c=new Component("ROOF","hall",new Vec3i(0,20,0),new Dimensions(width,depth,11),List.of(),params);
        return new RoofGenerator().generate(new SemanticComponent("ROOF",null,c));
    }

    private List<BlockPatch> windows(List<BlockPatch> patches,int y) {
        return patches.stream().filter(p -> p.dy()==y && p.targetBlock().startsWith("minecraft:glass_pane")).toList();
    }

    @Test void ordinaryRectangleSpreadsThreeDefaultWindowsWithOneCellBetweenTheirCheeks() {
        for(String face:List.of("north","south")) {
            var panes=windows(roof(17,13,3,7,Map.of("dormer_face",face)),28);
            var centers=panes.stream().map(BlockPatch::dx).sorted().toList();
            assertEquals(List.of(4,8,12),centers,face);
            for(int i=1;i<centers.size();i++) assertTrue(centers.get(i)-centers.get(i-1)>=4);
            assertTrue(panes.stream().allMatch(p -> p.dz()==(face.equals("north") ? 2 : 10)));
        }
    }

    @Test void deepFoldsReduceAutomaticCountButExplicitCountsAndDisableFlagsKeepTheirMeaning() {
        var two=windows(roof(11,15,4,9,Map.of()),30);
        assertEquals(List.of(5,9),two.stream().map(BlockPatch::dz).sorted().toList());
        assertTrue(two.stream().allMatch(p -> p.dx()==3));
        assertEquals(1,windows(roof(7,7,2,7,Map.of()),28).size());
        assertThrows(IllegalArgumentException.class,() -> roof(11,15,4,9,Map.of("mansard_dormer_count",3)));
        assertTrue(roof(11,15,4,9,Map.of("roof_dormers",false,"mansard_dormer_count",3)).stream()
                .noneMatch(p -> p.targetBlock().startsWith("minecraft:glass_pane")));
    }

    private List<BlockPatch> partialFold(int length) {
        var patches=new ArrayList<BlockPatch>();
        for(int z=5;z<5+length;z++) {
            patches.add(new BlockPatch(BlockPatch.PLACE,3,27,z,"minecraft:stone_bricks"));
            patches.add(new BlockPatch(BlockPatch.PLACE,4,28,z,"minecraft:stone_bricks"));
            patches.add(new BlockPatch(BlockPatch.PLACE,2,25,z,"minecraft:stone_bricks"));
        }
        return patches;
    }

    @Test void defaultDecoratorUsesTheExistingFoldInsteadOfInventingWindowsOverMissingRoof() {
        var profile=ComponentRoofSpecialtyDecorator.resolveMansardProfile(13,17,11,
                Map.of("mansard_break_run",3,"mansard_break_rise",7));
        var out=new ArrayList<BlockPatch>();
        ComponentRoofSpecialtyDecorator.emitDormers(out,partialFold(3),0,20,0,13,17,profile,
                "minecraft:stone_bricks","minecraft:glass_pane","minecraft:stone_bricks");
        var panes=windows(out,28);
        assertEquals(1,panes.size());
        assertEquals(6,panes.getFirst().dz());
        assertTrue(out.stream().allMatch(p -> p.dz()>=5 && p.dz()<=7));
        var rejected=new ArrayList<BlockPatch>();
        assertThrows(IllegalArgumentException.class,() -> ComponentRoofSpecialtyDecorator.emitDormers(rejected,
                partialFold(2),0,20,0,13,17,profile,"minecraft:stone_bricks","minecraft:glass_pane","minecraft:stone_bricks"));
        assertTrue(rejected.isEmpty());
    }
}
