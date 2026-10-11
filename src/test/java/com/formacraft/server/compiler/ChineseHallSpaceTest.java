package com.formacraft.server.compiler;

import com.formacraft.server.generation.typology.builder.TailiangTimberHallBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChineseHallSpaceTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    private static final BlockPos ORIGIN = new BlockPos(80,64,-120);
    private static BlockPos at(Direction facing,int x,int y,int z) {
        return switch(facing) {
            case NORTH -> ORIGIN.add(-x,y,-z);
            case EAST -> ORIGIN.add(z,y,-x);
            case WEST -> ORIGIN.add(-z,y,x);
            default -> ORIGIN.add(x,y,z);
        };
    }
    private static Map<BlockPos,BlockState> generate(Map<String,Object> params) {
        var states=new HashMap<BlockPos,BlockState>();
        TailiangTimberHallBuilder.generate(params,ORIGIN,null,null).getBlocks()
                .forEach(b->states.put(b.getPos(),b.getTargetState()));
        return states;
    }
    @Test void bayCountsCoverExactSpanAndRejectImpossibleCounts() {
        var axis=TailiangTimberHallBuilder.bayAxis(25,7);
        assertEquals(8,axis.length); assertEquals(0,axis[0]);assertEquals(24,axis[7]);
        for(int i=1;i<axis.length;i++) assertTrue(axis[i]-axis[i-1]>=2);
        assertThrows(IllegalArgumentException.class,()->TailiangTimberHallBuilder.bayAxis(15,9));
    }
    @Test void wallsAndWindowsPreserveColumnsAndEntranceCrossesPorchAcrossFacings() {
        for(var facing:List.of(Direction.SOUTH,Direction.NORTH,Direction.EAST,Direction.WEST))
            for(boolean sub:List.of(false,true)) {
                int offset=sub?3:0,outerW=25+2*offset,outerD=15+2*offset;
                var states=generate(Map.of("width",25,"depth",15,"anchorMode","corner","facing",facing.name(),
                        "includeSubEaves",sub));
                for(int x=0;x<outerW;x++) for(int z=0;z<outerD;z++)
                    assertFalse(states.get(at(facing,x,2,z)).isAir(),"complete platform floor");
                var xs=TailiangTimberHallBuilder.bayAxis(25,7);
                var zs=TailiangTimberHallBuilder.bayAxis(15,4);
                for(int x:xs) for(int z:new int[]{0,14})
                    assertTrue(states.get(at(facing,offset+x,5,offset+z)).isOf(Blocks.DARK_OAK_LOG),"window cannot replace column");
                for(int z:zs) for(int x:new int[]{0,24})
                    assertTrue(states.get(at(facing,offset+x,5,offset+z)).isOf(Blocks.DARK_OAK_LOG));
                for(int x=0;x<25;x++) assertFalse(states.get(at(facing,offset+x,3,offset)).isAir(),"first and last bays enclosed");
                for(int x=0;x<25;x++) for(int z:new int[]{0,14})
                    assertTrue(states.get(at(facing,offset+x,8,offset+z)).isOf(Blocks.DARK_OAK_PLANKS),"continuous connecting beam");
                int doorMin=offset+xs[3]+1,doorMax=offset+xs[4]-1;
                for(int x=doorMin;x<=doorMax;x++) for(int z=offset+14;z<outerD;z++) for(int y=3;y<=4;y++)
                    assertTrue(states.get(at(facing,x,y,z)).isAir(),"door and porch have two clear blocks");
                assertTrue(states.values().stream().noneMatch(s->s.isOf(Blocks.OAK_DOOR)),"no invalid single door half");
                var stair=states.get(at(facing,(doorMin+doorMax)/2,2,outerD));
                assertEquals(facing.getOpposite(),stair.get(Properties.HORIZONTAL_FACING));
                assertTrue(states.keySet().stream().anyMatch(p->p.getY()==73
                        && (states.get(p).isOf(Blocks.DEEPSLATE_TILES)||states.get(p).isOf(Blocks.DEEPSLATE_BRICK_STAIRS))),
                        "roof starts on the beam elevation, without applying height twice");
            }
    }
    @Test void windowAndEntranceOptOutsKeepSolidEnclosure() {
        var states=generate(Map.of("width",25,"depth",15,"anchorMode","corner","includeSubEaves",false,
                "includeWindows",false,"includeEntrance",false));
        assertTrue(states.values().stream().noneMatch(s->s.isOf(Blocks.IRON_BARS)));
        for(int x=0;x<25;x++) for(int z:new int[]{0,14}) for(int y=3;y<=5;y++)
            assertFalse(states.get(at(Direction.SOUTH,x,y,z)).isAir());
    }
}
