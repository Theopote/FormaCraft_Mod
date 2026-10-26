package com.formacraft.server.compiler;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.DecorDetailGenerator;
import com.formacraft.common.network.*;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class Live2250RegressionTest {
    @BeforeAll static void init() { MinecraftRegistryTestBootstrap.initialize(); }
    @AfterEach void clear() { AssemblyCompileDiagnostics.clear(); }
    private LlmPlan load(String name) throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
        try(var in=getClass().getResourceAsStream("/regressions/live-2250-"+name+".json")) {return mapper.readValue(in,LlmPlan.class);}
    }
    private List<com.formacraft.common.patch.BlockPatch> compile(LlmPlan p) {
        var blocks=ComponentPlanCompiler.compile(p,BlockPos.ORIGIN,null,null,false);
        assertNull(AssemblyCompileDiagnostics.get()); assertFalse(blocks.isEmpty()); return blocks;
    }
    @Test void patchWithoutWindowBlockStillProducesOnlyFourGlassCells() throws Exception {
        var method=com.formacraft.server.network.LlmPlanPreviewBuilder.class.getDeclaredMethod("sanitizeIncrementalPatchPlan",LlmPlan.class);
        method.setAccessible(true);
        var blocks=compile((LlmPlan)method.invoke(null,load("225424")));
        assertEquals(4,blocks.size());assertTrue(blocks.stream().allMatch(b->b.targetBlock().contains("glass")));
    }
    @Test void cabinOrdinaryDoorsAndWindowsUseNativeGeometry() throws Exception {
        var plan=load("225320");
        for(var c:plan.components()) if("FACADE_WINDOWS".equals(c.componentType()) || "ENTRANCE".equals(c.componentType()))
            assertTrue(NativeBuildingComponentPolicy.apply(c,plan).features().stream().noneMatch(f->f.startsWith("component_request:")));
        compile(plan);
    }
    @Test void actualStiltPostsReachFloorAndSuppressAutomaticSupports() throws Exception {
        var p=load("225620");assertTrue(LlmPlanTerrainBounds.hasAuthoredSupports(p));
        var blocks=compile(p);
        var cells=new HashMap<BlockPos,String>();for(var b:blocks) cells.put(new BlockPos(b.dx(),b.dy(),b.dz()),b.targetBlock());
        var a=p.layout().slots().getFirst().anchor();
        for(var c:p.components()) if(c.params()!=null && "stilt_post".equals(c.params().get("role"))) {
            var pos=c.relativePosition();
            for(int y=0;y<4;y++) assertTrue(cells.get(new BlockPos(a.x()+pos.x(),a.y()+y,a.z()+pos.z())).contains("oak_log"));
        }
        assertFalse(blocks.stream().anyMatch(b->b.dy()<a.y()+4 && b.targetBlock().contains("cobblestone")));
    }
    @Test void explicitCapturedIdRemainsOptInAndNativeErrorsHaveCorrectLabel() throws Exception {
        var plan=load("225320");var c=plan.components().stream().filter(t->"ENTRANCE".equals(t.componentType())).findFirst().orElseThrow();
        var captured=new Component(c.componentType(),c.slotId(),c.relativePosition(),c.dimensions(),
                List.of("component_request:{\"component_id\":\"custom-door\"}"),c.params());
        assertSame(captured,NativeBuildingComponentPolicy.apply(captured,plan));
        String message=com.formacraft.server.assembly.CapabilityGapMessages.formatPlayerMessage(
                new CapabilityGap("E_COMPONENT_GENERATION_FAILED","原生窗生成失败","components[]",List.of()));
        assertTrue(message.contains("建筑生成失败"));assertFalse(message.contains("ASSEMBLY"));assertFalse(message.contains("库构件"));
    }
    @Test void missingTurnBandIsRejectedByFinalConnectivityGate() throws Exception {
        var plan=load("231046");var blocks=compile(plan);
        var parts=new ArrayList<Component>(plan.components());
        for(int i=0;i<parts.size();i++) {
            var c=parts.get(i);
            if(c.params()!=null && "tower_n_stair_3".equals(c.params().get("component_id"))) {
                var params=new HashMap<String,Object>(c.params());params.put("compiler_connected_stair_chain",true);
                parts.set(i,new Component(c.componentType(),c.slotId(),c.relativePosition(),c.dimensions(),c.features(),params));
            }
        }
        var anchor=plan.layout().slots().stream().filter(s->"tower_n".equals(s.slotId())).findFirst().orElseThrow().anchor();
        var broken=blocks.stream().filter(b-> !(b.dx()>anchor.x() && b.dx()<anchor.x()+8
                && b.dz()>anchor.z() && b.dz()<anchor.z()+8 && b.dy()>=7 && b.dy()<=8)).toList();
        var slots=new HashMap<String,Slot>();for(var slot:plan.layout().slots()) slots.put(slot.slotId(),slot);
        assertFalse(StairChainCompiler.validateConnectivity(parts,broken,slots));
        assertEquals("E_STAIR_CHAIN_UNCONNECTED",AssemblyCompileDiagnostics.get().code());
    }
    @Test void actualPavilionsAreOpenOnAllFourSides() throws Exception {
        var p=load("230039");var blocks=compile(p);var cells=new HashMap<BlockPos,String>();
        for(var b:blocks) cells.put(new BlockPos(b.dx(),b.dy(),b.dz()),b.targetBlock());
        for(var c:p.components()) if("MASS_MAIN".equals(c.componentType())) {
            var a=p.layout().slots().stream().filter(t->t.slotId().equals(c.slotId())).findFirst().orElseThrow().anchor();
            for(int[] d:new int[][]{{0,2},{0,-2},{2,0},{-2,0}})
                for(int y=2;y<=3;y++) assertEquals("minecraft:air",cells.get(new BlockPos(a.x()+d[0],a.y()+y,a.z()+d[1])));
        }
    }
    @Test void bothTowerStairsHaveWalkableRouteFromGroundToTop() throws Exception {
        var p=load("231046");var blocks=compile(p);var cells=new HashMap<BlockPos,String>();
        for(var b:blocks) cells.put(new BlockPos(b.dx(),b.dy(),b.dz()),b.targetBlock());
        for(String name:List.of("tower_n","tower_s")) {
            var a=p.layout().slots().stream().filter(t->name.equals(t.slotId())).findFirst().orElseThrow().anchor();
            Set<BlockPos> walkable=new HashSet<>();
            for(int x=1;x<=7;x++) for(int z=1;z<=7;z++) for(int y=0;y<=19;y++) {
                BlockPos pos=new BlockPos(a.x()+x,a.y()+y,a.z()+z);
                if(solid(cells.get(pos)) && !solid(cells.get(pos.up())) && !solid(cells.get(pos.up(2)))) walkable.add(pos);
            }
            Set<BlockPos> reached=new HashSet<>();ArrayDeque<BlockPos> queue=new ArrayDeque<>();
            for(var pos:walkable) if(pos.getY()<=a.y()+1) {reached.add(pos);queue.add(pos);}
            while(!queue.isEmpty()) {
                var pos=queue.remove();
                for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) for(int dy=-1;dy<=1;dy++) {
                    var n=pos.add(d[0],dy,d[1]);if(walkable.contains(n) && reached.add(n)) queue.add(n);
                }
            }
            assertTrue(reached.stream().anyMatch(pos->pos.getY()>=a.y()+19),name+" must reach the top via real treads and landings");
        }
    }
    @Test void latestHuizhouHasSealableInwardLattice() throws Exception {
        var blocks=compile(load("230712"));
        var live=new HashMap<BlockPos,net.minecraft.block.BlockState>();
        int minX=blocks.stream().mapToInt(b->b.dx()).min().orElseThrow(),maxX=blocks.stream().mapToInt(b->b.dx()).max().orElseThrow();
        int minZ=blocks.stream().mapToInt(b->b.dz()).min().orElseThrow(),maxZ=blocks.stream().mapToInt(b->b.dz()).max().orElseThrow();
        int maxY=blocks.stream().mapToInt(b->b.dy()).max().orElseThrow();
        for(var p:BlockPos.iterate(new BlockPos(minX,0,minZ),new BlockPos(maxX,maxY,maxZ)))
            live.put(p.toImmutable(),net.minecraft.block.Blocks.AIR.getDefaultState());
        for(var b:blocks) live.put(new BlockPos(b.dx(),b.dy(),b.dz()),
                "minecraft:air".equals(b.targetBlock())?net.minecraft.block.Blocks.AIR.getDefaultState():
                com.formacraft.common.compiler.voxel.PaletteResolver.resolveFromBlockString(b.targetBlock()));
        var result=com.formacraft.server.edit.WindowSealCompiler.compile(live,BlockPos.ORIGIN,
                new BlockPos((minX+maxX)/2,0,(minZ+maxZ)/2),Set.of(),p->{
                    for(int y=p.getY()+1;y<=maxY;y++) if(!live.getOrDefault(new BlockPos(p.getX(),y,p.getZ()),net.minecraft.block.Blocks.AIR.getDefaultState()).isAir()) return false;
                    return true;
                },true,true);
        assertFalse(result.isEmpty(),"Actual inward-facing lattice must be identified");
        for(var b:result) {
            assertTrue(live.get(new BlockPos(b.dx(),b.dy(),b.dz())).getBlock() instanceof net.minecraft.block.FenceBlock);
            assertEquals("minecraft:white_concrete",b.targetBlock());
        }
    }
    private boolean solid(String s) {return s!=null && !s.contains(":air") && !s.contains("_fence") && !s.contains("_door") && !s.contains("glass") && !s.contains("iron_bars");}
}
