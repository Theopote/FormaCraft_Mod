package com.formacraft.server.compiler;
import com.formacraft.common.llm.dto.*;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class Live2221RegressionTest {
    @BeforeAll static void init() { MinecraftRegistryTestBootstrap.initialize(); }
    @AfterEach void clear() { AssemblyCompileDiagnostics.clear(); }
    private LlmPlan load(String name) throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
        try(var in=getClass().getResourceAsStream("/regressions/ingame_20261028_2221/"+name+".json")) {return mapper.readValue(in,LlmPlan.class);}
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
    @Test void cabinRejectsUnsupportedAdvisoryProgramButCompilesComponents() throws Exception {
        var p=load("221315"); assertFalse(p.hasValidPlanProgramContent()); assertTrue(p.usesComponentMode()); compile(p);
    }
    @Test void fortressOpensBothShellsAtGroundFloor() throws Exception {
        var blocks=compile(load("222144")); var cells=snapshot(blocks,BlockPos.ORIGIN);
        for(int z:new int[]{-11,-10,9,10}) for(int x=-2;x<=0;x++)for(int y=1;y<=2;y++) {
            var state=cells.get(new BlockPos(x,y,z));assertTrue(state==null||state.isAir(),"Portal blocked at "+x+","+y+","+z);
            var floor=cells.get(new BlockPos(x,0,z)); assertNotNull(floor);assertFalse(floor.isAir());
        }
    }
    @Test void hallGroundFloorWalksIntoBothTowers() throws Exception {
        var cells=snapshot(compile(load("222144")),BlockPos.ORIGIN);
        var reached=new HashSet<BlockPos>();var queue=new ArrayDeque<BlockPos>();
        var start=new BlockPos(0,1,0);reached.add(start);queue.add(start);
        while(!queue.isEmpty()) {
            var p=queue.remove();
            for(var dir:net.minecraft.util.math.Direction.Type.HORIZONTAL) {
                var n=p.offset(dir);if(reached.contains(n))continue;
                var floor=cells.get(n.down());var feet=cells.get(n);var head=cells.get(n.up());
                if(floor==null || floor.isAir() || feet!=null&&!feet.isAir() || head!=null&&!head.isAir())continue;
                reached.add(n);queue.add(n);
            }
        }
        assertTrue(reached.contains(new BlockPos(-1,1,11)),"South tower interior unreachable from hall");
        assertTrue(reached.contains(new BlockPos(-1,1,-12)),"North tower interior unreachable from hall");
    }
    @Test void japaneseGardenPreservesExistingApproachPath() throws Exception {
        var cells=snapshot(compile(load("234822")),BlockPos.ORIGIN);
        assertTrue(Set.of(net.minecraft.block.Blocks.COBBLESTONE,net.minecraft.block.Blocks.MOSSY_COBBLESTONE).contains(cells.get(new BlockPos(18,0,-1)).getBlock()), "Path material must survive the garden plate, allowing its weathered variant");
    }
    @Test void stupaBoundTiersJoinAtTopSeams() throws Exception { compile(load("234431")); }
    @Test void unconnectedDeclarationIsRejected() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var tree=mapper.valueToTree(load("222144"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.get("components").get(0).get("params").get("internal_openings").get(0)).put("side","WEST");
        var blocks=ComponentPlanCompiler.compile(mapper.treeToValue(tree,LlmPlan.class),BlockPos.ORIGIN,null,null,false);
        assertTrue(blocks.isEmpty());assertEquals("E_INTERNAL_PASSAGE",AssemblyCompileDiagnostics.get().code());
    }
}