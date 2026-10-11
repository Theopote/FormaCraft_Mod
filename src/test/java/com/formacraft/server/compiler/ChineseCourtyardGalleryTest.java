package com.formacraft.server.compiler;

import com.formacraft.server.generation.typology.builder.CourtyardCompoundBuilder;
import com.formacraft.server.generation.typology.builder.ChineseCourtyardGalleryGenerator;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChineseCourtyardGalleryTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    private static final BlockPos ORIGIN=new BlockPos(80,64,-120);
    private static Map<BlockPos,BlockState> states(Map<String,Object> params) {
        var result=new HashMap<BlockPos,BlockState>();
        CourtyardCompoundBuilder.generate(params,ORIGIN,null,null).getBlocks()
                .forEach(b->result.put(b.getPos(),b.getTargetState()));
        return result;
    }
    private static boolean air(Map<BlockPos,BlockState> states,BlockPos p) { return states.get(p)==null||states.get(p).isAir(); }
    private static boolean walkable(Map<BlockPos,BlockState> states,BlockPos p) {
        return !air(states,p)&&air(states,p.up())&&air(states,p.up(2));
    }

    @Test void roofedRingAndFourConnectionsRemainTraversableWithoutOverwritingRooms() {
        for(var facing:List.of(Direction.SOUTH,Direction.NORTH,Direction.EAST,Direction.WEST))
            for(boolean paths:List.of(false,true)) {
                var params=new HashMap<String,Object>(Map.of("width",48,"depth",64,"facing",facing.name(),
                        "includePaths",paths,"roomDefaults",Map.of("roofOverhang",2)));
                params.put("layout",Map.of("plan","ring_corridor"));
                params.put("gallery",Map.of("roof_block","minecraft:bricks","pillar_block","minecraft:acacia_log",
                        "beam_block","minecraft:birch_log"));
                var with=states(params);
                var layout=CourtyardCompoundBuilder.resolveLayout(48,64,facing,3,2,3);
                var ring=ChineseCourtyardGalleryGenerator.resolve(layout.parts(),2,3);
                // Every prior wall, window, roof or beam survives; only outside floor materials may change.
                for(var part:layout.parts()) {
                    int cx=(part.minX()+part.maxX())/2,cz=(part.minZ()+part.maxZ())/2;
                    Direction entrance=Math.abs(cx)>Math.abs(cz)?(cx<0?Direction.EAST:Direction.WEST)
                            :(cz<0?Direction.SOUTH:Direction.NORTH);
                    var options=com.formacraft.server.generation.typology.builder.ChineseCourtyardRoomGenerator.options(
                            Map.of("roofOverhang",2),Blocks.STONE.getDefaultState(),Blocks.OAK_PLANKS.getDefaultState(),
                            Blocks.OAK_PLANKS.getDefaultState(),Blocks.STONE.getDefaultState());
                    var expected=new HashMap<BlockPos,BlockState>();
                    com.formacraft.server.generation.typology.builder.ChineseCourtyardRoomGenerator.generate(
                            part.width(),part.depth(),entrance,part.role().equals("gate_house"),ORIGIN.add(part.corner()),options)
                            .forEach(b->expected.put(b.getPos(),b.getTargetState()));
                    for(var e:expected.entrySet()) if(e.getKey().getY()>ORIGIN.getY()&&!e.getValue().isAir())
                        assertEquals(e.getValue(),with.get(e.getKey()),"gallery must preserve authored room solids");
                }
                for(int x=ring.minX()-2;x<=ring.maxX()+2;x++) for(int z=ring.minZ()-2;z<=ring.maxZ()+2;z++) {
                    if(x>ring.minX()+2&&x<ring.maxX()-2&&z>ring.minZ()+2&&z<ring.maxZ()-2) continue;
                    var p=ORIGIN.add(x,0,z);
                    assertFalse(air(with,p),"continuous gallery floor");
                    assertTrue(with.get(p.up(5)).isOf(Blocks.BRICKS),"continuous thin roof including corners");
                }
                assertTrue(air(with,ORIGIN.up(5)),"courtyard center stays open to sky");
                for(int x=ring.minX();x<=ring.maxX();x++) for(int z:new int[]{ring.minZ(),ring.maxZ()})
                    for(int y=1;y<=3;y++) assertTrue(air(with,ORIGIN.add(x,y,z)),"three-high ring route");
                for(int z=ring.minZ();z<=ring.maxZ();z++) for(int x:new int[]{ring.minX(),ring.maxX()})
                    for(int y=1;y<=3;y++) assertTrue(air(with,ORIGIN.add(x,y,z)),"three-high ring route");
                assertTrue(with.values().stream().anyMatch(s->s.isOf(Blocks.ACACIA_LOG)),"actual gallery columns");
                assertTrue(with.values().stream().anyMatch(s->s.isOf(Blocks.BIRCH_LOG)),"actual continuous gallery edge beams");
                var start=ORIGIN.add(ring.minX(),0,ring.minZ());
                assertTrue(walkable(with,start));
                var visited=new HashSet<BlockPos>();var queue=new ArrayDeque<BlockPos>();visited.add(start);queue.add(start);
                while(!queue.isEmpty()) {
                    var p=queue.remove();
                    for(var direction:List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST)) {
                        var next=p.offset(direction);
                        if(walkable(with,next)&&visited.add(next)) queue.add(next);
                    }
                }
                for(int x:new int[]{ring.minX(),ring.maxX()}) for(int z:new int[]{ring.minZ(),ring.maxZ()})
                    assertTrue(visited.contains(ORIGIN.add(x,0,z)),"all ring corners connected");
                for(var part:layout.parts()) assertTrue(visited.contains(ORIGIN.add(part.corner()).add(part.width()/2,0,part.depth()/2)),
                        "covered route reaches "+part.role()+" "+facing);
            }
    }

    @Test void explicitDisableKeepsOriginalCompoundAndWideGalleryKeepsOpenCourtyard() {
        var params=new HashMap<String,Object>(Map.of("width",64,"depth",64,"includePaths",false));
        var without=states(params);
        params.put("layout",Map.of("plan","ring_corridor"));params.put("includeGallery",false);
        assertEquals(without,states(params));
        params.put("includeGallery",true);params.put("gallery",Map.of("clearWidth",5));
        var with=states(params);
        assertTrue(air(with,ORIGIN.up(5)));
        var ring=ChineseCourtyardGalleryGenerator.resolve(CourtyardCompoundBuilder.resolveLayout(64,64,Direction.SOUTH,3,1,5).parts(),1,5);
        for(int delta=-2;delta<=2;delta++) {
            var p=ORIGIN.add(ring.minX()+delta,0,0);
            assertTrue(walkable(with,p),"five-cell usable gallery width at connector");
        }
    }

    @Test void insufficientSpaceAndInvalidGalleryParametersAreExplicitlyRejected() {
        assertThrows(IllegalArgumentException.class,()->states(Map.of("width",32,"depth",32,"includeGallery",true,
                "roomDefaults",Map.of("roofOverhang",3),"gallery",Map.of("clearWidth",5))));
        assertThrows(IllegalArgumentException.class,()->states(Map.of("width",48,"depth",64,"includeGallery",true,
                "gallery",Map.of("clearWidth",4))));
        assertThrows(IllegalArgumentException.class,()->states(Map.of("width",48,"depth",64,"includeGallery",true,
                "gallery",Map.of("roof_block","minecraft:air"))));
        assertThrows(IllegalArgumentException.class,()->states(Map.of("gallery",Map.of("unsupported",true))));
    }
}
