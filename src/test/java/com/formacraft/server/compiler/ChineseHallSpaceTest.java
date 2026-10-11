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

    @Test void interiorColumnsAlignAndAllWalkableInteriorCellsRemainConnected() {
        for(var facing:List.of(Direction.SOUTH,Direction.NORTH,Direction.EAST,Direction.WEST))
            for(int height:List.of(5,7,12)) {
                var states=generate(Map.of("width",25,"depth",15,"anchorMode","corner","facing",facing.name(),
                        "hallHeight",height,"includeSubEaves",false));
                var xs=TailiangTimberHallBuilder.bayAxis(25,7);
                var zs=TailiangTimberHallBuilder.bayAxis(15,4);
                for(int xi=1;xi<xs.length-1;xi++) for(int zi=1;zi<zs.length-1;zi++)
                    assertTrue(states.get(at(facing,xs[xi],3,zs[zi])).isOf(Blocks.DARK_OAK_LOG));
                var reachable=new HashSet<BlockPos>(); var queue=new ArrayDeque<BlockPos>();
                var start=new BlockPos(12,0,14);reachable.add(start);queue.add(start);
                while(!queue.isEmpty()) {
                    var p=queue.removeFirst();
                    for(int[] delta:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                        var next=p.add(delta[0],0,delta[1]);
                        int x=next.getX(),z=next.getZ();
                        if(x<1||x>23||z<1||z>14||reachable.contains(next)) continue;
                        if(states.get(at(facing,x,3,z)).isAir() && states.get(at(facing,x,4,z)).isAir()) {
                            assertFalse(states.get(at(facing,x,2,z)).isAir());
                            reachable.add(next);queue.add(next);
                        }
                    }
                }
                for(int x=1;x<24;x++) for(int z=1;z<14;z++)
                    if(states.get(at(facing,x,3,z)).isAir()&&states.get(at(facing,x,4,z)).isAir())
                        assertTrue(reachable.contains(new BlockPos(x,0,z)),"all usable spaces connect to entrance");
                assertTrue(states.get(at(facing,0,5,1)).isOf(Blocks.IRON_BARS),"longitudinal beam cannot replace side window");
            }
    }

    @Test void frameLevelsHaveSupportedProgressivelyShorterBeamsAndRespectRotation() {
        for(var facing:List.of(Direction.SOUTH,Direction.NORTH,Direction.EAST,Direction.WEST)) {
            var states=generate(Map.of("width",25,"depth",15,"anchorMode","corner","facing",facing.name(),
                    "includeSubEaves",false,"frameLevels",3));
            var horizontal=facing.getAxis()==Direction.Axis.Z?Direction.Axis.X:Direction.Axis.Z;
            for(int level=0;level<3;level++) {
                int start=level*2,end=24-level*2,y=6+level;
                for(int x=start+1;x<end;x++) {
                    var beam=states.get(at(facing,x,y,4));
                    assertTrue(beam.isOf(Blocks.DARK_OAK_LOG));
                    boolean supportsNextTier=level<2&&(x==(level+1)*2||x==24-(level+1)*2);
                    assertEquals(supportsNextTier?Direction.Axis.Y:horizontal,beam.get(Properties.AXIS));
                }
                if(level>0) for(int x:new int[]{start,end})
                    assertFalse(states.get(at(facing,x,y-1,4)).isAir(),"upper beam ends contact lower beam");
            }
        }
    }

    @Test void interiorAndFrameOptOutsAreIndependentAndExcessiveLevelsAreRejected() {
        var columnsOnly=generate(Map.of("width",25,"depth",15,"anchorMode","corner","includeSubEaves",false,
                "includeTimberFrame",false));
        assertTrue(columnsOnly.get(at(Direction.SOUTH,3,3,4)).isOf(Blocks.DARK_OAK_LOG));
        assertTrue(columnsOnly.get(at(Direction.SOUTH,1,6,4)).isAir());
        var frameOnly=generate(Map.of("width",25,"depth",15,"anchorMode","corner","includeSubEaves",false,
                "includeInteriorColumns",false));
        assertTrue(frameOnly.get(at(Direction.SOUTH,3,3,4)).isAir());
        assertFalse(frameOnly.get(at(Direction.SOUTH,1,6,4)).isAir());
        assertThrows(IllegalArgumentException.class,()->generate(Map.of("width",25,"depth",15,
                "hallHeight",5,"frameLevels",4)));
    }
}
