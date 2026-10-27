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
class Live2202RegressionTest {
    @BeforeAll static void init() { MinecraftRegistryTestBootstrap.initialize(); }
    @AfterEach void clear() { AssemblyCompileDiagnostics.clear(); }
    private LlmPlan load(String name) throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
        try(var in=getClass().getResourceAsStream("/regressions/ingame_20261027_2202/"+name+".json")) {return mapper.readValue(in,LlmPlan.class);}
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


    private Map<BlockPos,net.minecraft.block.BlockState> cells(String file) throws Exception {return snapshot(compile(load(file)),BlockPos.ORIGIN);}
    private boolean air(Map<BlockPos,net.minecraft.block.BlockState> c,BlockPos p) {var s=c.get(p);return s==null||s.isAir()||s.getBlock() instanceof net.minecraft.block.DoorBlock;}
    @Test void cabinWindowsWorkWithTerrainBesideTheDoor() throws Exception {
        var c=cells("214646");
        for(int x=-8;x<=8;x++)for(int z=-7;z<=7;z++) {
            c.putIfAbsent(new BlockPos(x,-1,z),net.minecraft.block.Blocks.DIRT.getDefaultState());
            if(x<-4||x>4||z<-3||z>3)c.put(new BlockPos(x,0,z),net.minecraft.block.Blocks.GRASS_BLOCK.getDefaultState());
        }
        for(int w:new int[]{1,2}) {
            var result=com.formacraft.server.edit.WindowInsertCompiler.analyze(c,BlockPos.ORIGIN,Set.of(),w,w);
            if(result.patches().size()!=w*w)dump("cabin",c);
            assertEquals(w*w,result.patches().size(),result.toString());
            for(var p:result.patches())assertTrue(p.dy()>=1&&p.dy()<=2,"Keep floor and roof intact");
        }
    }
    @Test void entranceRetainsFloorAtInteriorLevel() throws Exception {
        var c=cells("214646");
        for(int x=-1;x<=0;x++) {
            assertTrue(c.get(new BlockPos(x,0,3)).isOpaque(),"Threshold floor block remains");
            assertTrue(air(c,new BlockPos(x,1,3)));assertTrue(air(c,new BlockPos(x,2,3)));
        }
    }
    @Test void stiltDoorAndApproachHaveTwoClearHeadCells() throws Exception {
        var c=cells("215013");dump("stilt",c);
        for(int z=2;z<=5;z++) {
            assertFalse(air(c,new BlockPos(0,4,z)),"Continuous floor at z="+z);
            assertTrue(air(c,new BlockPos(0,5,z)),"No raised lip or railing at z="+z);
            assertTrue(air(c,new BlockPos(0,6,z)),"Headroom at z="+z);
        }
    }
    @Test void adobeGuardsStandAboveSecondFloor() throws Exception {
        var c=cells("215840");int n=0;
        for(var e:c.entrySet())if(e.getValue().getBlock() instanceof net.minecraft.block.FenceBlock) {
            n++;assertEquals(5,e.getKey().getY(),"Guard must stand on floor y=4");
        }
        assertTrue(n>=6,"Keep guards around the stair opening");
    }
    @Test void towerPlatformAndConnectedFlightsCompile() throws Exception {cells("220228");}
    private void dump(String name,Map<BlockPos,net.minecraft.block.BlockState> c)throws Exception {
        var out=new StringBuilder();for(var e:c.entrySet())out.append(e.getKey().getX()).append(',').append(e.getKey().getY()).append(',').append(e.getKey().getZ()).append(',').append(e.getValue()).append('\n');
        java.nio.file.Files.writeString(java.nio.file.Path.of(".gradle/live2202-"+name+".csv"),out.toString());
    }
}
