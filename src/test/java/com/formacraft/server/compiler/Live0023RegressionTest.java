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
class Live0023RegressionTest {
    @BeforeAll static void init() { MinecraftRegistryTestBootstrap.initialize(); }
    @AfterEach void clear() { AssemblyCompileDiagnostics.clear(); }
    private LlmPlan load(String name) throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
        try(var in=getClass().getResourceAsStream("/regressions/ingame_20261028_0023/"+name+".json")) {return mapper.readValue(in,LlmPlan.class);}
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
    @Test void stiltExteriorStairFitsDoorOpening() throws Exception {
        var live=snapshot(compile(load("001437")),BlockPos.ORIGIN);
        for(int z=2;z<=4;z++)for(int y=4;y<=5;y++) {
            var state=live.get(new BlockPos(0,y,z));assertTrue(state==null||state.isAir()||state.getBlock() instanceof net.minecraft.block.DoorBlock,"Full stair-width doorway clearance");
        }
    }
    @Test void adobeWindowRowsUseHostFloors() throws Exception {
        var patches=compile(load("001952"));
        assertTrue(patches.stream().anyMatch(p->p.targetBlock().contains("glass")&&p.dy()>1&&p.dy()<5));
        assertTrue(patches.stream().anyMatch(p->p.targetBlock().contains("glass")&&p.dy()>5&&p.dy()<9));
    }
    @Test void smallTowersHaveValidStairEnvelope() throws Exception {
        var patches=compile(load("002322_normalized"));
        for(int side:new int[]{-1,1})assertTrue(patches.stream().anyMatch(p->p.targetBlock().contains("stairs")&&p.dy()==15&&Integer.signum(p.dx())==side));
    }
}
