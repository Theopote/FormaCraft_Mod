package com.formacraft.server.build;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.generation.component.util.ComponentRoofSpecialtyDecorator;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MansardDormerEmbeddingTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }

    private List<BlockPatch> roof(int width,int depth,int height,int fold,int rise,String face,Map<String,Object> extras) {
        var params=new HashMap<String,Object>(Map.of("roof_type","mansard","roof_block","minecraft:stone_bricks",
                "mansard_break_run",fold,"mansard_break_rise",rise,"mansard_dormer_count",1,"dormer_face",face));
        params.putAll(extras);
        var c=new Component("ROOF","hall",new Vec3i(0,20,0),new Dimensions(width,depth,height),List.of(),params);
        return new RoofGenerator().generate(new SemanticComponent("ROOF",null,c));
    }

    @Test void deepFoldsStopTheOpeningAtTheRidgeOnAllFourFaces() {
        for(String face:List.of("north","south","west","east")) {
            var patches=roof(13,17,11,5,9,face,Map.of());
            var openings=patches.stream().filter(p -> BlockPatch.REMOVE.equals(p.action())).toList();
            assertFalse(openings.isEmpty(),face);
            for(var p:openings) {
                int inward=switch(face) {case "north" -> p.dz();case "south" -> 16-p.dz();
                    case "west" -> p.dx();default -> 12-p.dx();};
                assertEquals(6,inward,face);
            }
        }
    }

    @Test void defaultDepthIsIndependentOfTheFoldAndAnExplicitDepthCanExtendItSafely() {
        var standard=roof(31,35,17,4,11,"north",Map.of());
        assertEquals(6,standard.stream().filter(p -> BlockPatch.REMOVE.equals(p.action()))
                .mapToInt(BlockPatch::dz).max().orElseThrow());
        var deeper=roof(31,35,17,4,11,"north",Map.of("mansard_dormer_embed_depth",3));
        assertEquals(7,deeper.stream().filter(p -> BlockPatch.REMOVE.equals(p.action()))
                .mapToInt(BlockPatch::dz).max().orElseThrow());
        for(Object invalid:List.of(0,-1,0.5,"invalid"))
            assertThrows(IllegalArgumentException.class,() -> roof(31,35,17,4,11,"north",
                    Map.of("mansard_dormer_embed_depth",invalid)));
        var error=assertThrows(IllegalArgumentException.class,() -> roof(13,17,11,5,9,"north",
                Map.of("mansard_dormer_embed_depth",2)));
        assertTrue(error.getMessage().contains("ridge"));
    }

    private List<BlockPatch> interruptedRoof(boolean descending) {
        var patches=new ArrayList<BlockPatch>();
        for(int z=5;z<=11;z++) {
            patches.add(new BlockPatch(BlockPatch.PLACE,2,25,z,"minecraft:stone_bricks"));
            patches.add(new BlockPatch(BlockPatch.PLACE,3,27,z,"minecraft:stone_bricks"));
            patches.add(new BlockPatch(BlockPatch.PLACE,4,28,z,"minecraft:stone_bricks"));
            patches.add(new BlockPatch(BlockPatch.PLACE,5,29,z,"minecraft:stone_bricks"));
        }
        patches.add(new BlockPatch(BlockPatch.REMOVE,5,29,11,"minecraft:air"));
        if(descending) patches.add(new BlockPatch(BlockPatch.PLACE,5,26,11,"minecraft:stone_bricks"));
        return patches;
    }

    @Test void missingOrDescendingSupportShortensDefaultsButRejectsExplicitDepthBeforeAnyWrites() {
        var profile=ComponentRoofSpecialtyDecorator.resolveMansardProfile(17,21,11,
                Map.of("mansard_break_run",3,"mansard_break_rise",7));
        for(boolean descending:List.of(false,true)) {
            var roof=interruptedRoof(descending);
            var out=new ArrayList<BlockPatch>();
            ComponentRoofSpecialtyDecorator.emitDormers(out,roof,0,20,0,17,21,profile,2,Map.of("dormer_axis","z"),
                    "minecraft:stone_bricks","minecraft:glass_pane","minecraft:stone_bricks");
            assertEquals(5,out.stream().filter(p -> BlockPatch.REMOVE.equals(p.action()) && p.dz()==6)
                    .mapToInt(BlockPatch::dx).max().orElseThrow());
            assertEquals(4,out.stream().filter(p -> BlockPatch.REMOVE.equals(p.action()) && p.dz()==10)
                    .mapToInt(BlockPatch::dx).max().orElseThrow());
            var rejected=new ArrayList<BlockPatch>();
            assertThrows(IllegalArgumentException.class,() -> ComponentRoofSpecialtyDecorator.emitDormers(rejected,roof,
                    0,20,0,17,21,profile,2,Map.of("dormer_axis","z","mansard_dormer_embed_depth",2),
                    "minecraft:stone_bricks","minecraft:glass_pane","minecraft:stone_bricks"));
            assertTrue(rejected.isEmpty());
        }
    }
}
