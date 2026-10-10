package com.formacraft.server.compiler;
import com.formacraft.server.generation.typology.builder.GothicCathedralHallBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GothicBuildingAcceptanceTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @Test void completeHallPreservesPortalNaveRoseAndTwinTowersAcrossSizesAndFacings() {
        for(int width:List.of(17,25,35)) for(String facing:List.of("SOUTH","NORTH","EAST","WEST")) {
            int depth=35, wallHeight=width==17?12:16;
            var origin=new BlockPos(20,50,-10);
            var building=GothicCathedralHallBuilder.generate(Map.of("width",width,"depth",depth,
                    "wallHeight",wallHeight,"facing",facing),origin,null,null);
            var finalStates=new HashMap<BlockPos,BlockState>();
            for(var block:building.getBlocks()) finalStates.put(block.getPos(),block.getTargetState());
            int center=width/2;
            for(int z=2;z<depth;z++) for(int y=3;y<=4;y++) {
                var state=finalStates.get(position(origin,facing,center,y,z));
                assertNotNull(state); assertTrue(state.isAir(),"portal-to-nave route must be open: "+width+" "+facing+" z="+z);
            }
            for(int x=center-1;x<=center+1;x++) for(int y=3;y<9;y++)
                assertTrue(finalStates.get(position(origin,facing,x,y,depth-1)).isAir(),"door opening must reach floor and stay clear");
            assertTrue(finalStates.entrySet().stream().anyMatch(e->e.getValue().getBlock().getTranslationKey().contains("stained_glass")),"rose/cathedral glazing must survive final writes");
            int radius=Math.min(Math.min(7,width/4),(wallHeight-8)/2);
            int roseY=11+radius;
            var roseGlass=finalStates.get(position(origin,facing,center+(radius>=3?1:0),roseY+(radius>=3?1:0),depth-1));
            assertNotNull(roseGlass);
            assertTrue(roseGlass.getBlock().getTranslationKey().contains("stained_glass"),"front rose glass must not be erased by its mullions");
            for(int x:List.of(1,width-2))
                assertFalse(finalStates.get(position(origin,facing,x,20,depth-3)).isAir(),"both front tower bodies must remain");
        }
    }
    @Test void optionalElementsCanBeRemovedWithoutBlockingTheHall() {
        var origin=BlockPos.ORIGIN;
        var building=GothicCathedralHallBuilder.generate(Map.of("width",25,"depth",35,"wallHeight",16,
                "includeRoseWindow",false,"includeButtresses",false,"includeTowers",false),origin,null,null);
        var states=new HashMap<BlockPos,BlockState>();
        for(var block:building.getBlocks()) states.put(block.getPos(),block.getTargetState());
        assertTrue(states.get(new BlockPos(1,20,32)).isAir(),"disabled tower body must be absent");
        assertTrue(states.get(new BlockPos(1,8,4)).isAir(),"disabled buttress pier must be absent");
        assertFalse(states.get(new BlockPos(13,16,34)).getBlock().getTranslationKey().contains("glass"),"disabled rose must retain the facade wall");
        for(int z=2;z<35;z++) for(int y=3;y<=4;y++) assertTrue(states.get(new BlockPos(12,y,z)).isAir());
    }
    private BlockPos position(BlockPos origin,String facing,int x,int y,int z) {
        return switch(facing) {
            case "NORTH" -> origin.add(-x,y,-z);
            case "EAST" -> origin.add(z,y,-x);
            case "WEST" -> origin.add(-z,y,x);
            default -> origin.add(x,y,z);
        };
    }
}
