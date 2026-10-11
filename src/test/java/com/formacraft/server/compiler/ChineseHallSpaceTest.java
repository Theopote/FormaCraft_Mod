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
    @Test void thinRoofCoversEveryColumnAndKeepsInteriorEmptyWithClosedGables() {
        for(var facing:List.of(Direction.SOUTH,Direction.NORTH,Direction.EAST,Direction.WEST)) {
            var states=generate(Map.of("width",25,"depth",15,"anchorMode","corner","facing",facing.name(),
                    "includeSubEaves",false));
            for(int x=-1;x<=25;x++) for(int z=-1;z<=15;z++) {
                int dx=Math.min(x+1,25-x),dz=Math.min(z+1,15-z);
                int top=9+(dz<3?Math.min(dx,dz):dx);
                assertFalse(states.get(at(facing,x,top,z)).isAir(),"roof covers full footprint");
            }
            for(int y=10;y<17;y++)
                assertTrue(states.get(at(facing,12,y,7)).isAir(),"roof volume remains empty");
            for(int z:new int[]{2,12}) for(int y=12;y<17;y++)
                assertTrue(states.get(at(facing,12,y,z)).isOf(Blocks.RED_TERRACOTTA),"upper gables closed with wall material");
        }
    }
    @Test void expandedCurvedRoofRotatesAroundUnchangedAnchor() {
        for(var facing:List.of(Direction.SOUTH,Direction.NORTH,Direction.EAST,Direction.WEST)) {
            var states=generate(Map.of("width",25,"depth",15,"anchorMode","corner","facing",facing.name(),
                    "includeSubEaves",false,"roofOverhang",3,"roofCurve",100,"roofCornerLift",3));
            for(int x:new int[]{-3,27}) for(int z:new int[]{-3,17})
                assertTrue(states.get(at(facing,x,12,z)).isOf(Blocks.DEEPSLATE_TILES));
            assertTrue(states.get(at(facing,12,10,7)).isAir());
            assertFalse(states.get(at(facing,12,2,7)).isAir());
        }
    }
    @Test void subEavesCoverCornersAndMeetMainRoofAtCommonElevation() {
        for(var facing:List.of(Direction.SOUTH,Direction.NORTH,Direction.EAST,Direction.WEST))
            for(int offset:new int[]{2,3,5}) {
                int outerW=25+2*offset,outerD=15+2*offset;
                var states=generate(Map.of("width",25,"depth",15,"anchorMode","corner","facing",facing.name(),
                        "includeSubEaves",true,"subEavesDepth",offset,"includeBrackets",false));
                for(int x=0;x<outerW;x++) for(int z=0;z<outerD;z++) {
                    if(x>=offset&&x<offset+25&&z>=offset&&z<offset+15) continue;
                    assertFalse(states.get(at(facing,x,9,z)).isAir(),"continuous annular roof includes corners and column line");
                }
                for(int y=3;y<=4;y++) for(int z=offset+14;z<outerD;z++)
                    assertTrue(states.get(at(facing,offset+12,y,z)).isAir(),"porch entrance remains clear");
            }
    }
    @Test void bracketsStayUnderRoofAndConnectToColumnsAcrossRoofVariants() {
        for(var facing:List.of(Direction.SOUTH,Direction.NORTH,Direction.EAST,Direction.WEST))
            for(String form:List.of("ying_shan","xuan_shan","xie_shan","wu_dian"))
                for(boolean sub:List.of(false,true)) for(int e:new int[]{0,3}) {
                    var params=new HashMap<String,Object>(Map.of("width",25,"depth",15,"anchorMode","corner",
                            "facing",facing.name(),"roofType",form,"includeSubEaves",sub,"roofOverhang",e,
                            "roofCurve",100,"includeBrackets",false));
                    if(e>0&&(form.equals("xie_shan")||form.equals("wu_dian"))) params.put("roofCornerLift",3);
                    var off=generate(params);
                    params.put("includeBrackets",true);
                    var on=generate(params);
                    var additions=new HashSet<BlockPos>();
                    for(var entry:on.entrySet()) {
                        var pos=entry.getKey();
                        if(entry.getValue().equals(off.get(pos))) continue;
                        additions.add(pos);
                        int y=pos.getY();
                        boolean roofAbove=false;
                        for(int roofY=y+1;roofY<=ORIGIN.getY()+35;roofY++) {
                            var state=off.get(new BlockPos(pos.getX(),roofY,pos.getZ()));
                            if(state!=null&&(state.isOf(Blocks.DEEPSLATE_TILES)||state.isOf(Blocks.DEEPSLATE_BRICK_STAIRS)
                                    ||state.isOf(Blocks.DEEPSLATE_TILE_SLAB))) {roofAbove=true;break;}
                        }
                        assertTrue(roofAbove,"every bracket change is below an existing roof");
                    }
                    for(var entry:off.entrySet()) if(entry.getKey().getY()>=ORIGIN.getY()+9&&
                            (entry.getValue().isOf(Blocks.DEEPSLATE_TILES)||entry.getValue().isOf(Blocks.DEEPSLATE_BRICK_STAIRS)
                                    ||entry.getValue().isOf(Blocks.DEEPSLATE_TILE_SLAB)))
                        assertEquals(entry.getValue(),on.get(entry.getKey()),"brackets cannot replace roof surface");
                    var reached=new HashSet<BlockPos>();var queue=new ArrayDeque<BlockPos>();
                    for(var pos:additions) for(var direction:Direction.values()) {
                        var neighbor=pos.offset(direction);var state=off.get(neighbor);
                        if(state!=null&&!state.isAir()&&!additions.contains(neighbor)) {
                            if(reached.add(pos)) queue.add(pos);
                        }
                    }
                    while(!queue.isEmpty()) {
                        var pos=queue.removeFirst();
                        for(var direction:Direction.values()) {
                            var next=pos.offset(direction);
                            if(additions.contains(next)&&reached.add(next)) queue.add(next);
                        }
                    }
                    assertEquals(additions.size(),reached.size(),"no isolated bracket blocks");
                }
    }
    @Test void explicitMaterialsAndIndependentPaintToggleReachFinalBlocks() {
        for(var facing:List.of(Direction.SOUTH,Direction.NORTH,Direction.EAST,Direction.WEST)) {
            var params=new HashMap<String,Object>(Map.of("width",25,"depth",15,"anchorMode","corner",
                    "facing",facing.name(),"includeSubEaves",true));
            params.putAll(Map.of("wall_block","minecraft:white_concrete","floor_block","minecraft:oak_planks",
                    "pillar_block","minecraft:oak_log","roof_block","minecraft:bricks",
                    "paint_block","minecraft:blue_terracotta","foundation_block","minecraft:stone"));
            var painted=generate(params);
            assertTrue(painted.get(at(facing,15,2,10)).isOf(Blocks.OAK_PLANKS));
            assertTrue(painted.get(at(facing,15,0,10)).isOf(Blocks.STONE));
            assertTrue(painted.get(at(facing,3,5,3)).isOf(Blocks.OAK_LOG));
            assertTrue(painted.get(at(facing,4,3,3)).isOf(Blocks.WHITE_CONCRETE));
            assertTrue(painted.values().stream().anyMatch(s->s.isOf(Blocks.BLUE_TERRACOTTA)));
            assertFalse(painted.entrySet().stream().anyMatch(e->e.getKey().getY()>=ORIGIN.getY()+9&&
                    (e.getValue().isOf(Blocks.DEEPSLATE_TILES)||e.getValue().isOf(Blocks.DEEPSLATE_BRICK_STAIRS)
                            ||e.getValue().isOf(Blocks.DEEPSLATE_TILE_SLAB))),"roof family follows explicit primary material");
            params.put("includePainting",false);
            var plain=generate(params);
            assertFalse(plain.values().stream().anyMatch(s->s.isOf(Blocks.BLUE_TERRACOTTA)));
            assertTrue(plain.values().stream().anyMatch(s->s.isOf(Blocks.SPRUCE_STAIRS)),"paint opt-out retains brackets");
            for(var entry:painted.entrySet()) if(entry.getValue().isOf(Blocks.BRICKS))
                assertEquals(entry.getValue(),plain.get(entry.getKey()),"paint opt-out keeps roof");
        }
    }
    @Test void explicitInvalidMaterialFailsInsteadOfFallbackOrAir() {
        for(String id:List.of("minecraft:unknown_block","minecraft:air","minecraft:water","not a block",""))
            assertThrows(IllegalArgumentException.class,()->generate(Map.of("wall_block",id)));
    }
    @Test void buildingSpecMaterialsReachNativeHallAndExtraWins() {
        var spec=new com.formacraft.common.model.build.BuildingSpec();
        var materials=new com.formacraft.common.model.build.Materials();
        materials.setWall("minecraft:white_concrete");materials.setFoundation("minecraft:stone_bricks");
        spec.setMaterials(materials);
        var params=com.formacraft.common.typology.TypologyParamResolver.fromBuildingSpec(spec,"tailiang_timber_hall");
        assertEquals("minecraft:white_concrete",params.get("wall_block"));
        assertEquals("minecraft:stone_bricks",params.get("foundation_block"));
        assertTrue(TailiangTimberHallBuilder.fromBuildingSpec(spec,ORIGIN,null).getBlocks().stream()
                .anyMatch(b->b.getTargetState().isOf(Blocks.WHITE_CONCRETE)));
        spec.setExtra(Map.of("wall_block","minecraft:bricks"));
        assertEquals("minecraft:bricks",com.formacraft.common.typology.TypologyParamResolver
                .fromBuildingSpec(spec,"tailiang_timber_hall").get("wall_block"));
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
