package com.formacraft.server.compiler;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.server.generation.structure.ChineseHallRoofGenerator;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChineseRoofFormsTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    private Map<BlockPos,BlockState> roof(String form,int width,int depth,int rise) {
        return roof(form,width,depth,rise,new ChineseHallRoofGenerator.Shape(1,0,0));
    }
    private Map<BlockPos,BlockState> roof(String form,int width,int depth,int rise,ChineseHallRoofGenerator.Shape shape) {
        var blocks=new ArrayList<PlannedBlock>();
        ChineseHallRoofGenerator.generate(blocks,width,depth,0,form,rise,shape,
                Blocks.DEEPSLATE_TILES.getDefaultState(),Blocks.DEEPSLATE_BRICK_STAIRS.getDefaultState(),
                Blocks.RED_TERRACOTTA.getDefaultState());
        var states=new HashMap<BlockPos,BlockState>();
        blocks.forEach(b->states.put(b.getPos(),b.getTargetState()));
        return states;
    }
    @Test void formsHaveDifferentEndsAndWallClosures() {
        var hard=roof("ying_shan",25,15,13);
        var hanging=roof("xuan_shan",25,15,13);
        var combined=roof("xie_shan",25,15,13);
        var hip=roof("wu_dian",25,15,8);
        assertFalse(hard.keySet().stream().anyMatch(p->p.getZ()==-1));
        assertTrue(hanging.containsKey(new BlockPos(12,13,-1)));
        assertTrue(hard.get(new BlockPos(12,5,0)).isOf(Blocks.RED_TERRACOTTA));
        assertTrue(combined.get(new BlockPos(12,5,2)).isOf(Blocks.RED_TERRACOTTA));
        assertFalse(hip.values().stream().anyMatch(s->s.isOf(Blocks.RED_TERRACOTTA)));
        assertTrue(hip.containsKey(new BlockPos(12,0,-1)));
        assertFalse(combined.equals(hanging));
    }
    @Test void sizeAndRiseKeepContinuousThinSurfaceWithoutFixedHeightPlateau() {
        for(int width:new int[]{15,25,35}) for(int depth:new int[]{11,15,25})
            for(String form:List.of("ying_shan","xuan_shan","xie_shan","wu_dian")) {
                int run=form.equals("wu_dian")?Math.min((width+1)/2,(depth+1)/2):(width+1)/2;
                for(int rise:new int[]{3,run,18}) {
                    var states=roof(form,width,depth,rise);
                    int minZ=form.equals("ying_shan")?0:-1,maxZ=form.equals("ying_shan")?depth-1:depth;
                    for(int x=-1;x<=width;x++) for(int z=minZ;z<=maxZ;z++) {
                        int dx=Math.min(x+1,width-x),dz=Math.min(z+1,depth-z);
                        int dist=form.equals("wu_dian")||form.equals("xie_shan")&&dz<3?Math.min(dx,dz):dx;
                        int top=dist*rise/run;
                        assertNotNull(states.get(new BlockPos(x,top,z)),"every column has a roof surface");
                    }
                    assertTrue(states.containsKey(new BlockPos(width/2,rise,depth/2)));
                    assertFalse(states.containsKey(new BlockPos(width/2,0,depth/2)),"central roof cavity stays open");
                    if(rise==run&&!form.equals("wu_dian"))
                        assertFalse(states.containsKey(new BlockPos(width/2-1,rise,depth/2)),"default roof has a single ridge");
                    int thickness=Math.max(1,(rise+run-1)/run);
                    for(int x=-1;x<width;x++) {
                        int left=Math.min(x+1,width-x)*rise/run;
                        int right=Math.min(x+2,width-x-1)*rise/run;
                        assertTrue(Math.abs(left-right)<=thickness,"risers bridge adjacent surface steps");
                    }
                }
            }
    }
    @Test void unknownRoofDoesNotSilentlyBecomeAnotherForm() {
        assertEquals("ying_shan",ChineseHallRoofGenerator.normalize("硬山"));
        assertEquals("wu_dian",ChineseHallRoofGenerator.normalize("wudian"));
        assertDoesNotThrow(()->com.formacraft.server.generation.typology.builder.TailiangTimberHallBuilder.generate(
                com.formacraft.common.typology.TypologyParamResolver.merge("tailiang_timber_hall",null),
                new BlockPos(20,70,30),null,null));
        assertThrows(IllegalArgumentException.class,()->roof("unknown",25,15,13));
    }

    @Test void eavesAndCurveChangeGeometryAndCornersRemainConnected() {
        for(String form:List.of("ying_shan","xuan_shan","xie_shan","wu_dian"))
            for(int e=0;e<=3;e++) for(int curve:new int[]{0,100}) for(int lift:new int[]{0,3}) {
                boolean supported=lift==0||e>0&&(form.equals("xie_shan")||form.equals("wu_dian"));
                var shape=new ChineseHallRoofGenerator.Shape(e,curve,lift);
                if(!supported) {
                    assertThrows(IllegalArgumentException.class,()->roof(form,25,15,18,shape));
                    continue;
                }
                var states=roof(form,25,15,18,shape);
                assertEquals(-e,states.keySet().stream().mapToInt(BlockPos::getX).min().orElseThrow());
                assertEquals(24+e,states.keySet().stream().mapToInt(BlockPos::getX).max().orElseThrow());
                assertEquals(form.equals("ying_shan")?0:-e,
                        states.keySet().stream().mapToInt(BlockPos::getZ).min().orElseThrow());
                assertFalse(states.containsKey(new BlockPos(12,0,7)),"curve preserves central cavity");
                var visited=new HashSet<BlockPos>();
                var queue=new ArrayDeque<BlockPos>();
                BlockPos first=states.keySet().iterator().next();queue.add(first);visited.add(first);
                while(!queue.isEmpty()) {
                    var pos=queue.removeFirst();
                    for(var direction:net.minecraft.util.math.Direction.values()) {
                        var next=pos.offset(direction);
                        if(states.containsKey(next)&&visited.add(next)) queue.addLast(next);
                    }
                }
                assertEquals(states.size(),visited.size(),"surface, gables and lifted corners form one connected shell");
                if(lift>0) for(int x:new int[]{-e,24+e}) for(int z:new int[]{-e,14+e})
                    assertNotNull(states.get(new BlockPos(x,3,z)),"all corners lift by requested amount");
            }
        var straight=roof("xuan_shan",25,15,13,new ChineseHallRoofGenerator.Shape(1,0,0));
        var curved=roof("xuan_shan",25,15,13,new ChineseHallRoofGenerator.Shape(1,100,0));
        assertTrue(straight.containsKey(new BlockPos(3,4,7)));
        assertFalse(curved.containsKey(new BlockPos(3,4,7)),"curved lower slope is shallower");
        assertTrue(curved.containsKey(new BlockPos(12,13,7)),"curve keeps ridge height");
    }
}
