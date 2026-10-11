package com.formacraft.server.compiler;

import com.formacraft.server.generation.typology.builder.ChineseCourtyardRoomGenerator;
import com.formacraft.server.generation.typology.builder.CourtyardCompoundBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChineseCourtyardRoomTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    private static final BlockPos ANCHOR=new BlockPos(80,64,-120);
    private static boolean air(Map<BlockPos,BlockState> states,BlockPos p) {
        return states.get(p)==null||states.get(p).isAir();
    }
    private static boolean walkable(Map<BlockPos,BlockState> states,BlockPos floor) {
        return states.get(floor)!=null&&!states.get(floor).isAir()&&air(states,floor.up())&&air(states,floor.up(2));
    }

    @Test void finalCompoundConnectsCenterToAllFourNativeRoomsWithoutRemovingTheirWalls() {
        for(var facing:List.of(Direction.SOUTH,Direction.NORTH,Direction.EAST,Direction.WEST))
            for(int[] size:List.of(new int[]{32,32},new int[]{36,44},new int[]{45,61})) {
                var generated=CourtyardCompoundBuilder.generate(Map.of("width",size[0],"depth",size[1],
                        "facing",facing.name(),"includePaths",true),ANCHOR,null,null);
                assertEquals(ANCHOR,generated.getOrigin());
                assertTrue(generated.getDescription().contains("专用木构"));
                var states=new HashMap<BlockPos,BlockState>();
                generated.getBlocks().forEach(b->states.put(b.getPos(),b.getTargetState()));
                assertTrue(walkable(states,ANCHOR));
                var reached=new HashSet<BlockPos>();var queue=new ArrayDeque<BlockPos>();
                reached.add(ANCHOR);queue.add(ANCHOR);
                while(!queue.isEmpty()) {
                    var p=queue.remove();
                    for(var direction:List.of(Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST)) {
                        var next=p.offset(direction);
                        if(walkable(states,next)&&reached.add(next)) queue.add(next);
                    }
                }
                var layout=CourtyardCompoundBuilder.resolveLayout(size[0],size[1],facing,3);
                for(var part:layout.parts()) {
                    var corner=ANCHOR.add(part.corner());
                    assertTrue(reached.contains(corner.add(part.width()/2,0,part.depth()/2)),
                            "center to room interior: "+facing+" "+part.role());
                    for(int x=0;x<part.width();x++) for(int z=0;z<part.depth();z++)
                    {
                        assertFalse(states.get(corner.add(x,0,z)).isAir(),"complete shared-level room floor");
                        if(x==0||x==part.width()-1||z==0||z==part.depth()-1)
                            assertFalse(air(states,corner.add(x,4,z)),"continuous walls/lintels above the openings");
                    }
                    for(int x:new int[]{0,part.width()-1}) for(int z:new int[]{0,part.depth()-1})
                        for(int y=1;y<=5;y++) assertTrue(states.get(corner.add(x,y,z)).isOf(Blocks.DARK_OAK_LOG),
                                "four corner columns survive compound merging");
                    assertTrue(air(states,corner.add(part.width()/2,7,part.depth()/2)),"roof cavity remains hollow");
                }
            }
    }

    @Test void dedicatedGateHasTwoWideThreeHighPassageAndHardGablesAcrossFacingsAndEvenSizes() {
        for(var facing:List.of(Direction.SOUTH,Direction.NORTH,Direction.EAST,Direction.WEST))
            for(int[] size:List.of(new int[]{7,7},new int[]{10,8},new int[]{15,11})) {
                int width=size[0],depth=size[1];
                var states=new HashMap<BlockPos,BlockState>();
                ChineseCourtyardRoomGenerator.generate(width,depth,facing,true,ANCHOR,
                        Blocks.WHITE_CONCRETE.getDefaultState(),Blocks.BRICKS.getDefaultState(),
                        Blocks.OAK_PLANKS.getDefaultState(),Blocks.STONE_BRICKS.getDefaultState())
                        .forEach(b->states.put(b.getPos(),b.getTargetState()));
                boolean alongZ=facing.getAxis()==Direction.Axis.Z;
                int cross=alongZ?width:depth,run=alongZ?depth:width;
                for(int axis=cross/2-1;axis<=cross/2;axis++) for(int r=0;r<run;r++) {
                    var p=ANCHOR.add(alongZ?axis:r,0,alongZ?r:axis);
                    for(int y=1;y<=3;y++) assertTrue(air(states,p.up(y)),"through passage");
                    assertFalse(states.get(p).isAir());
                }
                // Hard-gable end faces stop at the body's facade-width endpoints.
                for(var e:states.entrySet()) if(e.getValue().isOf(Blocks.BRICKS)) {
                    var p=e.getKey().subtract(ANCHOR);
                    assertTrue(alongZ?p.getX()>=0&&p.getX()<width:p.getZ()>=0&&p.getZ()<depth);
                }
                var center=ANCHOR.add(width/2,7,depth/2);
                assertTrue(air(states,center));
            }
    }

    @Test void unsupportedRoomPartitionIsRejectedBeforePartialGeneration() {
        assertThrows(IllegalArgumentException.class,()->CourtyardCompoundBuilder.generate(
                Map.of("width",36,"depth",44,"layout",Map.of("plan","front_back")),ANCHOR,null,null));
    }
}
