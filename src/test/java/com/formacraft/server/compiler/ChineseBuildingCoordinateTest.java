package com.formacraft.server.compiler;

import com.formacraft.server.generation.typology.builder.TailiangTimberHallBuilder;
import com.formacraft.server.generation.typology.builder.CourtyardCompoundBuilder;
import com.formacraft.common.model.build.BuildingSpec;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChineseBuildingCoordinateTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @Test void courtyardLayoutRejectsImpossibleFootprintsAndKeepsRoofEnvelopesSeparate() {
        for(var facing:List.of(net.minecraft.util.math.Direction.NORTH,net.minecraft.util.math.Direction.SOUTH,
                net.minecraft.util.math.Direction.EAST,net.minecraft.util.math.Direction.WEST)) {
            assertThrows(IllegalArgumentException.class,()->CourtyardCompoundBuilder.resolveLayout(20,20,facing,3));
            for(int width:List.of(32,36,45,64,96)) for(int depth:List.of(32,44,61,96)) {
                var layout=CourtyardCompoundBuilder.resolveLayout(width,depth,facing,3);
                assertEquals(4,layout.parts().size());
                for(int i=0;i<layout.parts().size();i++) {
                    var a=layout.parts().get(i);
                    assertTrue(a.minX()-1>-width/2 && a.maxX()+1<-width/2+width-1);
                    assertTrue(a.minZ()-1>-depth/2 && a.maxZ()+1<-depth/2+depth-1);
                    for(int j=0;j<i;j++) {
                        var b=layout.parts().get(j);
                        assertTrue(a.maxX()+1<b.minX()-1 || b.maxX()+1<a.minX()-1
                                || a.maxZ()+1<b.minZ()-1 || b.maxZ()+1<a.minZ()-1,
                                "roof footprints cannot overlap: "+a.role()+" / "+b.role());
                    }
                }
            }
        }
    }

    @Test void compactLayoutReducesDerivedChildrenWithoutChangingRequestedEnclosure() {
        var layout=CourtyardCompoundBuilder.resolveLayout(32,32,net.minecraft.util.math.Direction.SOUTH,3);
        assertTrue(layout.scalePercent()<100);
        var anchor=new BlockPos(80,64,-120);
        var generated=CourtyardCompoundBuilder.generate(Map.of("width",32,"depth",32,
                "includePaths",true,"facing","SOUTH"),anchor,null,null);
        assertEquals(anchor,generated.getOrigin());
        var states=new HashMap<BlockPos,BlockState>();
        generated.getBlocks().forEach(b->states.put(b.getPos(),b.getTargetState()));
        assertFalse(states.get(anchor.add(-16,0,0)).isAir());
        assertFalse(states.get(anchor.add(15,0,0)).isAir());
        assertFalse(states.get(anchor).isAir());
    }
    @Test void hallCenterAndInternalCornerHaveIdenticalTranslatedGeometry() {
        for(String facing:List.of("SOUTH","NORTH","EAST","WEST")) for(boolean sub:List.of(false,true)) {
            var anchor=new BlockPos(80,64,-120);
            int hx=(25+(sub?6:0))/2,hz=(15+(sub?6:0))/2;
            var corner=switch(facing) {
                case "NORTH" -> anchor.add(hx,0,hz);
                case "EAST" -> anchor.add(-hz,0,hx);
                case "WEST" -> anchor.add(hz,0,-hx);
                default -> anchor.add(-hx,0,-hz);
            };
            var params=new HashMap<String,Object>(Map.of("width",25,"depth",15,"facing",facing,"includeSubEaves",sub));
            var centered=TailiangTimberHallBuilder.generate(params,anchor,null,null);
            params.put("anchorMode","corner");
            var internal=TailiangTimberHallBuilder.generate(params,corner,null,null);
            var a=new HashMap<BlockPos,BlockState>(); var b=new HashMap<BlockPos,BlockState>();
            centered.getBlocks().forEach(p->a.put(p.getPos(),p.getTargetState()));
            internal.getBlocks().forEach(p->b.put(p.getPos(),p.getTargetState()));
            assertEquals(a,b); assertEquals(anchor,centered.getOrigin());
            assertNotNull(a.get(anchor)); assertFalse(a.get(anchor).isAir(),"anchor on foundation center");
        }
    }
    @Test void courtyardUsesExplicitDimensionsAndBoundsThem() {
        var spec=new BuildingSpec();
        spec.setExtra(Map.of("width",45,"depth",61));
        assertArrayEquals(new int[]{45,61},CourtyardCompoundBuilder.resolvedDimensions(spec));
        spec.setExtra(Map.of("width",2,"depth",200));
        assertArrayEquals(new int[]{16,96},CourtyardCompoundBuilder.resolvedDimensions(spec));
    }

    @Test void courtyardGateHonorsFacingAndLayoutPrecedence() {
        var spec=new BuildingSpec();
        for(String facing:List.of("NORTH","SOUTH","EAST","WEST")) {
            spec.setExtra(Map.of("facing",facing));
            assertEquals(net.minecraft.util.math.Direction.valueOf(facing),CourtyardCompoundBuilder.resolveGateSide(spec));
        }
        spec.setExtra(Map.of("facing","NORTH","doorSide","东"));
        assertEquals(net.minecraft.util.math.Direction.EAST,CourtyardCompoundBuilder.resolveGateSide(spec));
        spec.setExtra(Map.of("facing","NORTH","doorSide","EAST","layout",Map.of("entranceFacing","西")));
        assertEquals(net.minecraft.util.math.Direction.WEST,CourtyardCompoundBuilder.resolveGateSide(spec));
        spec.setExtra(Map.of("facing","UP"));
        assertEquals(net.minecraft.util.math.Direction.SOUTH,CourtyardCompoundBuilder.resolveGateSide(spec));
    }

    @Test void enclosureKeepsExactEvenAndOddDimensionsAndGateClearance() {
        for(int width:List.of(20,21,44,45)) for(int depth:List.of(30,31))
            for(var facing:List.of(net.minecraft.util.math.Direction.NORTH,net.minecraft.util.math.Direction.SOUTH,
                    net.minecraft.util.math.Direction.EAST,net.minecraft.util.math.Direction.WEST)) {
                var plan=new com.formacraft.common.skeleton.rect.RectEnclosurePlan(width,depth,3,1,facing,3);
                var origin=new BlockPos(80,64,-120);
                var blocks=new com.formacraft.server.skeleton.rect.RectEnclosureInterpreter(
                        net.minecraft.block.Blocks.STONE_BRICKS.getDefaultState(),net.minecraft.block.Blocks.STONE_BRICK_SLAB.getDefaultState())
                        .interpret(plan,origin,null);
                assertEquals(width,blocks.stream().mapToInt(b->b.getPos().getX()).max().orElseThrow()
                        -blocks.stream().mapToInt(b->b.getPos().getX()).min().orElseThrow()+1);
                assertEquals(depth,blocks.stream().mapToInt(b->b.getPos().getZ()).max().orElseThrow()
                        -blocks.stream().mapToInt(b->b.getPos().getZ()).min().orElseThrow()+1);
                var gate=switch(facing) {
                    case NORTH -> origin.add(0,0,-depth/2);
                    case EAST -> origin.add(width-width/2-1,0,0);
                    case WEST -> origin.add(-width/2,0,0);
                    default -> origin.add(0,0,depth-depth/2-1);
                };
                var states=new HashMap<BlockPos,BlockState>();
                blocks.forEach(b->states.put(b.getPos(),b.getTargetState()));
                assertTrue(states.get(gate.up()).isAir());
                assertTrue(states.get(gate.up(2)).isAir());
            }
    }

    @Test void hallLandingUsesActualFoundationEnvelopeAcrossFacings() {
        for(String facing:List.of("SOUTH","NORTH","EAST","WEST")) for(boolean sub:List.of(false,true)) {
            var params=Map.<String,Object>of("width",25,"depth",15,"facing",facing,"includeSubEaves",sub);
            var component=new com.formacraft.common.llm.dto.Component("STRUCTURE",null,
                    new com.formacraft.common.llm.dto.Vec3i(0,0,0),
                    new com.formacraft.common.llm.dto.Dimensions(90,90,90),
                    List.of("typology:tailiang_timber_hall"),params);
            var plan=com.formacraft.common.llm.dto.LlmPlanTestFixtures.builder()
                    .mode(com.formacraft.common.llm.dto.LlmPlan.Mode.build).components(List.of(component)).build();
            var anchor=new BlockPos(80,64,-120);
            var sites=com.formacraft.server.terrain.BuildingLandingPlanner.sites(plan,anchor);
            assertEquals(1,sites.size());
            var bounds=sites.getFirst().body();
            var generated=TailiangTimberHallBuilder.generate(params,anchor,null,null);
            var states=new HashMap<BlockPos,BlockState>();
            generated.getBlocks().forEach(b->states.put(b.getPos(),b.getTargetState()));
            long foundation=states.entrySet().stream().filter(e->e.getKey().getY()==64 && !e.getValue().isAir()
                    && e.getKey().getX()>=bounds.minX() && e.getKey().getX()<=bounds.maxX()
                    && e.getKey().getZ()>=bounds.minZ() && e.getKey().getZ()<=bounds.maxZ()).count();
            assertEquals((long)bounds.width()*bounds.depth(),foundation);
            for(int x=bounds.minX();x<=bounds.maxX();x++) for(int z=bounds.minZ();z<=bounds.maxZ();z++)
                assertFalse(states.get(new BlockPos(x,64,z)).isAir());
            assertEquals(anchor.getX(),(bounds.minX()+bounds.maxX())/2);
            assertEquals(anchor.getZ(),(bounds.minZ()+bounds.maxZ())/2);
        }
    }

    @Test void siblingClearanceCannotDeleteEarlierWallsButOwnDoorCarvingSurvives() {
        var first=new com.formacraft.common.skeleton.rect.RectEnclosurePlan(20,30,3,1,null,3);
        var second=new com.formacraft.common.skeleton.rect.RectEnclosurePlan(21,31,3,1,null,3);
        var compound=new com.formacraft.common.skeleton.compound.CompoundPlan()
                .add(first,com.formacraft.common.skeleton.transform.BlockTransform.identity())
                .add(second,com.formacraft.common.skeleton.transform.BlockTransform.translate(2,0,0));
        var wall=net.minecraft.block.Blocks.STONE_BRICKS.getDefaultState();
        var air=net.minecraft.block.Blocks.AIR.getDefaultState();
        var output=new com.formacraft.server.skeleton.compound.CompoundInterpreter((plan,o,world)->
                plan==first?List.of(new com.formacraft.common.build.PlannedBlock(o.add(2,1,0),wall)):
                        List.of(new com.formacraft.common.build.PlannedBlock(o.up(),air),
                                new com.formacraft.common.build.PlannedBlock(o.add(1,1,0),wall),
                                new com.formacraft.common.build.PlannedBlock(o.add(1,1,0),air)),true)
                .interpret(compound,BlockPos.ORIGIN,null);
        var states=new HashMap<BlockPos,BlockState>();
        output.forEach(b->states.put(b.getPos(),b.getTargetState()));
        assertEquals(wall,states.get(new BlockPos(2,1,0)));
        assertTrue(states.get(new BlockPos(3,1,0)).isAir());
    }

    @Test void actualCourtyardUsesOneFloorAndLandingFillsShallowTerrainGaps() {
        var anchor=new BlockPos(80,64,-120);
        var params=Map.<String,Object>of("width",45,"depth",61,"facing","SOUTH","includePaths",true);
        var generated=CourtyardCompoundBuilder.generate(params,anchor,null,null);
        var spec=new BuildingSpec();spec.setExtra(params);
        var site=CourtyardCompoundBuilder.landingSite(spec,anchor);
        var component=new com.formacraft.common.llm.dto.Component("STRUCTURE",null,
                new com.formacraft.common.llm.dto.Vec3i(0,0,0),new com.formacraft.common.llm.dto.Dimensions(90,90,90),
                List.of("typology:courtyard_compound"),params);
        var plan=com.formacraft.common.llm.dto.LlmPlanTestFixtures.builder()
                .mode(com.formacraft.common.llm.dto.LlmPlan.Mode.build).components(List.of(component)).build();
        assertEquals(List.of(site),com.formacraft.server.terrain.BuildingLandingPlanner.sites(plan,anchor),
                "native courtyard enters unified landing instead of legacy blanket padding");
        var ground=new com.formacraft.server.terrain.BuildingLandingPlanner.Ground() {
            public int surfaceY(int x,int z) { return (x+z)%7==0?63:64; }
            public BlockState state(BlockPos p) { return p.getY()<surfaceY(p.getX(),p.getZ())
                    ?net.minecraft.block.Blocks.STONE.getDefaultState():net.minecraft.block.Blocks.AIR.getDefaultState(); }
            public int bottomY() { return -64; }
        };
        var result=com.formacraft.server.terrain.BuildingLandingPlanner.prepare(generated.getBlocks(),List.of(site),ground,
                com.formacraft.common.llm.dto.GlobalConstraints.TerrainStrategy.ADAPTIVE,false,
                net.minecraft.block.Blocks.COBBLESTONE.getDefaultState());
        assertNull(result.problem());assertEquals(0,result.dy());
        var states=new HashMap<BlockPos,BlockState>();
        generated.getBlocks().forEach(b->states.put(b.getPos(),b.getTargetState()));
        assertFalse(states.get(anchor).isAir(),"central path is on shared floor elevation");
        assertFalse(states.get(anchor.add(0,0,-28)).isAir(),"main hall is placed by its corner, not shifted again");
        var landed=new HashMap<BlockPos,BlockState>();result.blocks().forEach(b->landed.put(b.getPos(),b.getTargetState()));
        for(var e:states.entrySet()) if(e.getKey().getY()==64 && !e.getValue().isAir()
                && ground.surfaceY(e.getKey().getX(),e.getKey().getZ())==63) {
            assertNotNull(landed.get(e.getKey().down()));
            assertFalse(landed.get(e.getKey().down()).isAir(),"shallow gap below authored paving/foundation is filled");
        }
    }

    @Test void courtyardGatehouseHasContinuousTwoHighAxialPassageAcrossFacings() {
        for(String facing:List.of("SOUTH","NORTH","EAST","WEST")) {
            var anchor=new BlockPos(80,64,-120);
            var generated=CourtyardCompoundBuilder.generate(Map.of("width",45,"depth",61,
                    "facing",facing,"includePaths",true),anchor,null,null);
            var states=new HashMap<BlockPos,BlockState>();
            generated.getBlocks().forEach(b->states.put(b.getPos(),b.getTargetState()));
            int limit=facing.equals("NORTH")||facing.equals("SOUTH")?30:22;
            for(int run=-limit+3;run<limit;run++) {
                var p=switch(facing) {
                    case "NORTH" -> anchor.add(0,0,-run);
                    case "EAST" -> anchor.add(run,0,0);
                    case "WEST" -> anchor.add(-run,0,0);
                    default -> anchor.add(0,0,run);
                };
                assertNotNull(states.get(p),"paved gate-to-center route "+p);
                assertFalse(states.get(p).isAir(),"route floor "+p);
                for(int y=1;y<=2;y++) assertTrue(states.get(p.up(y))==null || states.get(p.up(y)).isAir(),
                        "gatehouse must not block axial route "+facing+" "+p.up(y));
            }
        }
    }
}
