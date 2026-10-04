package com.formacraft.server.terrain;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.network.LlmPlanTerrainBounds.Bounds;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.function.IntBinaryOperator;
import static org.junit.jupiter.api.Assertions.*;

class BuildingLandingPlannerTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    private static BuildingLandingPlanner.Ground ground(IntBinaryOperator height) {
        return new BuildingLandingPlanner.Ground() {
            public int surfaceY(int x,int z) { return height.applyAsInt(x,z); }
            public BlockState state(BlockPos p) { return p.getY()<surfaceY(p.getX(),p.getZ())
                    ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState(); }
            public int bottomY() { return -64; }
        };
    }
    private static BuildingLandingPlanner.Site site(int x,int floor) {
        return new BuildingLandingPlanner.Site(new Bounds(x,floor,0,x+8,floor+5,6),GlobalConstraints.Facing.NORTH);
    }
    private static List<PlannedBlock> house(BuildingLandingPlanner.Site site,boolean doorway) {
        var b=site.body();
        List<PlannedBlock> out=new ArrayList<>();
        for(int x=b.minX();x<=b.maxX();x++) for(int z=b.minZ();z<=b.maxZ();z++) {
            out.add(new PlannedBlock(new BlockPos(x,b.minY(),z),Blocks.STONE_BRICKS.getDefaultState()));
            out.add(new PlannedBlock(new BlockPos(x,b.maxY(),z),Blocks.OAK_PLANKS.getDefaultState()));
            for(int y=b.minY()+1;y<b.maxY();y++) out.add(new PlannedBlock(new BlockPos(x,y,z),
                    x==b.minX()||x==b.maxX()||z==b.minZ()||z==b.maxZ()
                            ? Blocks.STONE_BRICKS.getDefaultState() : Blocks.AIR.getDefaultState()));
        }
        if(doorway) for(int y=b.minY()+1;y<=b.minY()+2;y++)
            out.add(new PlannedBlock(new BlockPos(b.minX()+4,y,b.minZ()),Blocks.AIR.getDefaultState()));
        return out;
    }
    private static BuildingLandingPlanner.Result prepare(List<PlannedBlock> blocks,List<BuildingLandingPlanner.Site> sites,
                                                          BuildingLandingPlanner.Ground ground) {
        return BuildingLandingPlanner.prepare(blocks,sites,ground,GlobalConstraints.TerrainStrategy.ADAPTIVE,
                false,Blocks.COBBLESTONE.getDefaultState());
    }
    private static Map<BlockPos,BlockState> finalMap(List<PlannedBlock> blocks) {
        Map<BlockPos,BlockState> out=new HashMap<>();
        blocks.forEach(b->out.put(b.getPos(),b.getTargetState()));
        return out;
    }
    @Test void flatGroundAddsNoExtraSlabAndPreservesRooms() {
        var s=site(0,64); var result=prepare(house(s,true),List.of(s),ground((x,z)->64));
        assertNull(result.problem()); assertEquals(0,result.dy()); assertEquals(0,result.supports());
        var blocks=finalMap(result.blocks());
        assertTrue(blocks.get(new BlockPos(4,65,3)).isAir());
        assertFalse(blocks.get(new BlockPos(4,64,3)).isAir());
        assertEquals(1,result.steps());
    }
    @Test void elevatedAnchorMovesTheWholeHouseIncludingAirToGround() {
        var s=site(0,85); var input=house(s,false);
        var result=prepare(input,List.of(s),ground((x,z)->64));
        assertNull(result.problem()); assertEquals(-21,result.dy());
        var blocks=finalMap(result.blocks());
        assertTrue(blocks.get(new BlockPos(4,65,3)).isAir());
        assertTrue(blocks.get(new BlockPos(4,69,3)).isOf(Blocks.OAK_PLANKS));
    }
    @Test void gentleSlopeFillsToGroundAndCutsWithoutMovingIndividualColumns() {
        var s=site(0,64); var result=prepare(house(s,false),List.of(s),ground((x,z)->60+x));
        assertNull(result.problem()); assertTrue(result.dy()>=0 && result.dy()<=4);
        var blocks=finalMap(result.blocks());
        assertTrue(blocks.get(new BlockPos(2,62,2)).isOf(Blocks.COBBLESTONE));
        assertTrue(blocks.get(new BlockPos(7,65+result.dy(),3)).isAir());
        for(int x=0;x<=8;x++) assertTrue(blocks.get(new BlockPos(x,69+result.dy(),3)).isOf(Blocks.OAK_PLANKS));
    }
    @Test void cliffUsesSparsePiersInsteadOfSolidRetainingWall() {
        var s=site(0,64); var result=prepare(house(s,false),List.of(s),ground((x,z)->x<4?54:64));
        assertNull(result.problem());
        var blocks=finalMap(result.blocks());
        assertTrue(blocks.get(new BlockPos(2,54,2)).isOf(Blocks.COBBLESTONE));
        assertNull(blocks.get(new BlockPos(1,54,3)));
        assertTrue(result.supports()<4*7*10);
    }
    @Test void highCliffAnchorsDiagonalBracesIntoTheMountain() {
        var s=site(0,64); var input=house(s,false);
        var result=prepare(input,List.of(s),ground((x,z)->x<4?40:64));
        assertNull(result.problem());
        var blocks=finalMap(result.blocks());
        assertTrue(blocks.get(new BlockPos(3,62,2)).isOf(Blocks.COBBLESTONE));
        assertTrue(blocks.get(new BlockPos(3,63,2)).isOf(Blocks.COBBLESTONE));
        assertNull(blocks.get(new BlockPos(2,40,2)),"brace avoids a full-height pillar");
    }
    @Test void veryHighCliffCanUseNearbyRockInsteadOfPillarsToTheValley() {
        var s=site(0,164);
        var result=prepare(house(s,false),List.of(s),ground((x,z)->x<4?-60:164));
        assertNull(result.problem());
        var blocks=finalMap(result.blocks());
        assertTrue(blocks.get(new BlockPos(3,162,2)).isOf(Blocks.COBBLESTONE));
        assertNull(blocks.get(new BlockPos(2,0,2)));
    }
    @Test void separateBuildingsDoNotClearOrPaveTheGap() {
        var a=site(0,64); var b=site(30,64);
        var input=new ArrayList<>(house(a,false));input.addAll(house(b,false));
        var result=prepare(input,List.of(a,b),ground((x,z)->x>10&&x<28?70:64));
        assertNull(result.problem());
        assertTrue(result.blocks().stream().noneMatch(p->p.getPos().getX()>10&&p.getPos().getX()<28));
    }
    @Test void riverKeepsFloorAboveWaterAndAnchorsPiersToBed() {
        var s=site(0,64);
        var water=new BuildingLandingPlanner.Ground() {
            public int surfaceY(int x,int z) {return z<0?64:60;}
            public int placementY(int x,int z) {return 64;}
            public int bottomY(){return -64;}
            public BlockState state(BlockPos p){return p.getY()<surfaceY(p.getX(),p.getZ())?Blocks.STONE.getDefaultState()
                    :p.getY()<64?Blocks.WATER.getDefaultState():Blocks.AIR.getDefaultState();}
        };
        var result=prepare(house(s,true),List.of(s),water);
        assertNull(result.problem());assertEquals(0,result.dy());
        assertTrue(finalMap(result.blocks()).get(new BlockPos(2,60,2)).isOf(Blocks.COBBLESTONE));
    }
    @Test void entranceStepsDescendOneBlockAtATimeAndReachGround() {
        var s=site(0,64);var result=prepare(house(s,true),List.of(s),ground((x,z)->z<0?60:64));
        assertNull(result.problem());assertEquals(5,result.steps());
        var blocks=finalMap(result.blocks());
        var terrain=ground((x,z)->z<0?60:64);
        var queue=new ArrayDeque<BlockPos>(); var reached=new HashSet<BlockPos>();
        queue.add(new BlockPos(4,64,0));boolean connected=false;
        while(!queue.isEmpty()) {
            var p=queue.remove();if(!reached.add(p))continue;
            if(p.getZ()<0&&p.getY()==59){connected=true;break;}
            for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})for(int rise=-1;rise<=1;rise++) {
                var next=p.add(d[0],rise,d[1]);
                if(next.getY()<59||next.getY()>64||next.getX()<-3||next.getX()>12||next.getZ()<-10||next.getZ()>0)continue;
                if(!blocks.getOrDefault(next,terrain.state(next)).isAir()
                        &&blocks.getOrDefault(next.up(),terrain.state(next.up())).isAir()
                        &&blocks.getOrDefault(next.up(2),terrain.state(next.up(2))).isAir())queue.add(next);
            }
        }
        assertTrue(connected,"one-block steps and two-block headroom form a route to natural ground");
    }
    @Test void existingPorchIsCrossedBeforeStepsDescend() {
        var s=site(0,64);var input=new ArrayList<>(house(s,true));
        for(int z=-2;z<0;z++)for(int x=0;x<=8;x++)input.add(new PlannedBlock(new BlockPos(x,64,z),Blocks.STONE_BRICKS.getDefaultState()));
        var result=prepare(input,List.of(s),ground((x,z)->z<0?60:64));
        assertNull(result.problem());assertEquals(7,result.steps());
        assertTrue(finalMap(result.blocks()).get(new BlockPos(4,63,-3)).isOf(Blocks.COBBLESTONE));
    }
    @Test void preservePolicyLeavesThePlanUntouched() {
        var s=site(0,85);var input=house(s,true);
        var result=BuildingLandingPlanner.prepare(input,List.of(s),ground((x,z)->64),GlobalConstraints.TerrainStrategy.PRESERVE,
                false,Blocks.COBBLESTONE.getDefaultState());
        assertSame(input,result.blocks());assertEquals(0,result.dy());
    }
    @ParameterizedTest @ValueSource(ints={1,2,3,4,5,6})
    void actualLoggedPlansLandWithoutChangingCompiledGeometry(int id) throws Exception {
        try(var in=getClass().getResourceAsStream("/style_log_cases_round4/case_"+id+".json")) {
            var plan=com.formacraft.common.llm.parser.LlmPlanParser.parseAndValidate(
                    new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            var patches=com.formacraft.server.compiler.ComponentPlanCompiler.compile(plan,BlockPos.ORIGIN,null,null,false);
            var input=com.formacraft.server.network.PlanPatchConverter.convert(patches,BlockPos.ORIGIN).blocks();
            var sites=BuildingLandingPlanner.sites(plan,BlockPos.ORIGIN);
            assertFalse(sites.isEmpty());
            var result=prepare(input,sites,ground((x,z)->64));
            assertNull(result.problem(),result.problem());
            var finalBlocks=finalMap(result.blocks());
            for(var b:finalMap(input).entrySet()) assertEquals(b.getValue(),finalBlocks.get(b.getKey().up(result.dy())),
                    "authored solid / air must win over earthwork at "+b.getKey());
        }
    }
    @Test void entranceFacingEastUsesDepthAxisAndReachesGround() {
        var s=new BuildingLandingPlanner.Site(new Bounds(0,64,0,8,69,6),GlobalConstraints.Facing.EAST);
        var input=new ArrayList<>(house(s,false));
        input.add(new PlannedBlock(new BlockPos(8,65,3),Blocks.AIR.getDefaultState()));
        input.add(new PlannedBlock(new BlockPos(8,66,3),Blocks.AIR.getDefaultState()));
        var result=prepare(input,List.of(s),ground((x,z)->x>8?62:64));
        assertNull(result.problem());assertEquals(3,result.steps());
        assertTrue(finalMap(result.blocks()).get(new BlockPos(9,63,3)).isOf(Blocks.COBBLESTONE));
    }
    @Test void cannotPreviewStepsClippedBySelection() {
        var s=site(0,64);var request=new com.formacraft.common.model.request.FormaRequest(
                "test",BlockPos.ORIGIN,"NORTH","minecraft:overworld",null,new BlockPos(0,60,0),new BlockPos(8,70,6));
        var result=com.formacraft.server.build.BuildConstraintContext.withRequest(request,
                ()->prepare(house(s,true),List.of(s),ground((x,z)->64)));
        assertNotNull(result.problem());
    }
    @Test void patchPlansDoNotAcquireAutomaticLandingTranslations() {
        var component=new com.formacraft.common.llm.dto.Component("MASS_MAIN",null,
                new com.formacraft.common.llm.dto.Vec3i(0,0,0),new com.formacraft.common.llm.dto.Dimensions(9,7,6),null,null);
        var plan=com.formacraft.common.llm.dto.LlmPlanTestFixtures.builder().mode(com.formacraft.common.llm.dto.LlmPlan.Mode.patch)
                .components(List.of(component)).build();
        assertTrue(BuildingLandingPlanner.sites(plan,BlockPos.ORIGIN).isEmpty());
    }
    @ParameterizedTest @ValueSource(ints={1,2,3,4,5,6})
    void latestTerrainLogsCanLandOnSlopeCliffAndRiver(int id) throws Exception {
        try(var in=getClass().getResourceAsStream("/terrain_log_cases/case_"+id+".json")) {
            var plan=com.formacraft.common.llm.parser.LlmPlanParser.parseAndValidate(
                    new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            BlockPos origin=new BlockPos(plan.anchor().x(),plan.anchor().y(),plan.anchor().z());
            var sites=BuildingLandingPlanner.sites(plan,origin);
            assertEquals(id==5?2:1,sites.size(),"floor plates cannot become sites");
            var input=com.formacraft.server.network.PlanPatchConverter.convert(
                    com.formacraft.server.compiler.ComponentPlanCompiler.compile(plan,origin,null,null,false),origin).blocks();
            assertFalse(input.isEmpty(),()->String.valueOf(com.formacraft.server.assembly.AssemblyCompileDiagnostics.get()));
            var b=sites.getFirst().body();int center=(b.minX()+b.maxX())/2,base=b.minY();
            if(id==1) assertEquals((long)b.width()*b.depth(),finalMap(input).entrySet().stream()
                    .filter(e->e.getKey().getY()==base-1&&!e.getValue().isAir()).count(),
                    "automatic foundation must not leave a decorative ring outside the house");
            for(int terrain=0;terrain<3;terrain++) {
                final int scenario=terrain;
                BuildingLandingPlanner.Ground land=ground((x,z)->scenario==0?base+Math.floorDiv(x-center,3):x<center?base-30:base);
                if(terrain==2) land=new BuildingLandingPlanner.Ground() {
                    public int surfaceY(int x,int z){return x<center?base-6:base;}
                    public int placementY(int x,int z){return base;}
                    public int bottomY(){return -64;}
                    public BlockState state(BlockPos p){return p.getY()<surfaceY(p.getX(),p.getZ())?Blocks.STONE.getDefaultState()
                            :p.getY()<base&&p.getX()<center?Blocks.WATER.getDefaultState():Blocks.AIR.getDefaultState();}
                };
                var result=prepare(input,sites,land);
                assertNull(result.problem(),"case "+id+", scenario "+terrain+": "+result.problem());
                assertTrue(result.steps()>=sites.size(),"each real entrance must have an access route");
                var finalBlocks=finalMap(result.blocks());
                for(var e:finalMap(input).entrySet())
                    assertEquals(e.getValue(),finalBlocks.get(e.getKey().up(result.dy())));
            }
        }
    }
}
