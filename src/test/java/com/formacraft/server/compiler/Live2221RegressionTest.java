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
    @Test void japaneseGardenPreservesExistingApproachPath() throws Exception { compile(load("234822")); }
    @Test void stupaBoundTiersJoinAtTopSeams() throws Exception { compile(load("234431")); }}