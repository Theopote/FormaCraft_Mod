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
class Live1027RegressionTest {
    @BeforeAll static void init() { MinecraftRegistryTestBootstrap.initialize(); }
    @AfterEach void clear() { AssemblyCompileDiagnostics.clear(); }
    private LlmPlan load(String name) throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
        try(var in=getClass().getResourceAsStream("/regressions/ingame_20261027/"+name+".json")) {return mapper.readValue(in,LlmPlan.class);}
    }
    private List<com.formacraft.common.patch.BlockPatch> compile(LlmPlan p) {
        var blocks=ComponentPlanCompiler.compile(p,BlockPos.ORIGIN,null,null,false);
        assertNull(AssemblyCompileDiagnostics.get()); assertFalse(blocks.isEmpty()); return blocks;
    }
    private Map<BlockPos,net.minecraft.block.BlockState> snapshot(List<com.formacraft.common.patch.BlockPatch> blocks,BlockPos origin) {
        var result=new HashMap<BlockPos,net.minecraft.block.BlockState>();
        for(var b:blocks) result.put(origin.add(b.dx(),b.dy(),b.dz()),com.formacraft.common.patch.BlockPatchTargetResolver.resolve(b));
        return result;
    }
    @Test void windowAttachesToLiveWallAfterTerrainTranslation() throws Exception {
        var origin=new BlockPos(333,86,-1133);var live=snapshot(compile(load("235436")),origin);
        var analyzed=com.formacraft.server.edit.WindowInsertCompiler.analyze(live,origin,Set.of(),2,2);
        var result=analyzed.patches();assertEquals(4,result.size(),analyzed.toString());
        for(var b:result) {
            var state=live.get(origin.add(b.dx(),b.dy(),b.dz()));
            assertTrue(state.isOpaque());assertFalse(state.getBlock() instanceof net.minecraft.block.DoorBlock);
            assertTrue(b.dy()>0,"Window must not be placed on the ground");
        }
    }
    @Test void stiltFoundationIsNineWoodPostsAndLeavesTheUnderfloorVoid() throws Exception {
        var plan=load("235925");assertTrue(LlmPlanTerrainBounds.hasAuthoredSupports(plan));
        var blocks=compile(plan);var live=snapshot(blocks,BlockPos.ORIGIN);
        for(int x=-3;x<=3;x++) for(int z=-3;z<=3;z++) for(int y=0;y<4;y++) {
            var state=live.get(new BlockPos(x,y,z));
            if((x==-3 || x==0 || x==3) && (z==-3 || z==0 || z==3)) assertTrue(state!=null && state.isOf(net.minecraft.block.Blocks.OAK_LOG),"Full-height wood post at "+x+","+y+","+z);
            else assertTrue(state==null || state.isAir(),"Preserve void between posts at "+x+","+y+","+z+":"+state);
        }
        assertFalse(blocks.stream().anyMatch(b->b.dy()<4 && b.targetBlock().contains("cobblestone")));
    }
    @Test void pavilionWallTypeNoneProducesAirAndPosts() throws Exception {
        var plan=load("000302");var live=snapshot(compile(plan),BlockPos.ORIGIN);
        for(var c:plan.components()) if("MASS_MAIN".equals(c.componentType())) {
            var a=plan.layout().slots().stream().filter(t->t.slotId().equals(c.slotId())).findFirst().orElseThrow().anchor();
            for(int[] d:new int[][]{{0,2},{0,-2},{2,0},{-2,0}}) for(int y=2;y<=3;y++)
                assertTrue(live.get(new BlockPos(a.x()+d[0],y,a.z()+d[1])).isAir());
        }
    }
    @Test void centralSkywellDoesNotOverwriteAnyWing() throws Exception {
        compile(load("000853"));
    }
    @Test void adobeStairEndsOnActualSecondFloorBlockPlane() throws Exception {
        var plan=load("001342");var blocks=compile(plan);
        assertTrue(blocks.stream().anyMatch(b->b.dy()==5 && b.targetBlock().contains("oak_stairs")));
        assertFalse(blocks.stream().anyMatch(b->b.dy()>5 && b.targetBlock().contains("oak_stairs")),"Arrival cannot be above the authored second floor at y=5");
    }
    @Test void hallConnectsToBothTowerInteriorsWithoutGoingOutside() throws Exception {
        var plan=load("002003");var live=snapshot(compile(plan),BlockPos.ORIGIN);
        var walk=new HashSet<BlockPos>();
        // Restrict to the interiors and their two shared wall planes. Outdoor detours cannot pass.
        for(int x=-16;x<=16;x++) for(int z=-4;z<=4;z++) {
            var p=new BlockPos(x,0,z);var floor=live.get(p);
            if(floor!=null && !floor.isAir() && air(live,p.up()) && air(live,p.up(2)))walk.add(p);
        }
        var reached=new HashSet<BlockPos>();var q=new ArrayDeque<BlockPos>();
        q.add(BlockPos.ORIGIN);reached.add(BlockPos.ORIGIN);
        while(!q.isEmpty()) {
            var p=q.remove();for(var n:List.of(p.north(),p.south(),p.east(),p.west())) if(walk.contains(n) && reached.add(n))q.add(n);
        }
        assertTrue(reached.contains(new BlockPos(-12,0,0)),"Reach west tower through both wall shells");
        assertTrue(reached.contains(new BlockPos(12,0,0)),"Reach east tower through both wall shells");
    }
    @Test void windowRefusesUnlocatedEntranceAndProtectedRoof() throws Exception {
        var compilerOrigin=new BlockPos(333,86,-1133);
        var live=snapshot(compile(load("235436")),compilerOrigin);
        assertTrue(com.formacraft.server.edit.WindowInsertCompiler.compile(Map.of(),compilerOrigin,Set.of(),2,2).isEmpty());
        assertTrue(com.formacraft.server.edit.WindowInsertCompiler.compile(live,compilerOrigin,live.keySet(),2,2).isEmpty());
        assertTrue(com.formacraft.server.edit.WindowInsertCompiler.compile(live,compilerOrigin,Set.of(),9,2).isEmpty());
    }
    private boolean air(Map<BlockPos,net.minecraft.block.BlockState> live,BlockPos p) {
        var s=live.get(p);return s==null || s.isAir() || s.getBlock() instanceof net.minecraft.block.DoorBlock;
    }
}
