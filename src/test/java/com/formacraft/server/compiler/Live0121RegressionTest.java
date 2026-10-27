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
class Live0121RegressionTest {
    @BeforeAll static void init() { MinecraftRegistryTestBootstrap.initialize(); }
    @AfterEach void clear() { AssemblyCompileDiagnostics.clear(); }
    private LlmPlan load(String name) throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
        try(var in=getClass().getResourceAsStream("/regressions/ingame_20261027_0105/"+name+".json")) {return mapper.readValue(in,LlmPlan.class);}
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

    @Test void woodenLatticeMaterialAuditStillRejectsPostprocessReplacement() {
        var c=new Component("FACADE_WINDOWS","slot",new Vec3i(0,0,0),new Dimensions(1,2,2),List.of(),
            Map.of("component_id","lattice","window_style","wooden_lattice","lattice_material","minecraft:dark_oak_planks","glass_material","minecraft:glass_pane"));
        var expected=new HashMap<BlockPos,OpeningMaterialAudit.Expectation>();
        expected.put(BlockPos.ORIGIN,new OpeningMaterialAudit.Expectation("lattice",
            com.formacraft.common.generation.component.util.GeneratedSurfaceCapture.Role.WINDOW,
            com.formacraft.common.generation.component.util.WoodLatticeMaterial.resolve(c.params())));
        var good=OpeningMaterialAudit.inspect(expected,List.of(new com.formacraft.common.patch.BlockPatch(com.formacraft.common.patch.BlockPatch.PLACE,0,0,0,"minecraft:dark_oak_fence")));
        assertTrue(good.isMatched());
        var bad=OpeningMaterialAudit.inspect(expected,List.of(new com.formacraft.common.patch.BlockPatch(com.formacraft.common.patch.BlockPatch.PLACE,0,0,0,"minecraft:glass_pane")));
        assertEquals(1,bad.mismatchCells());
    }
    @Test void narrowCabinWallDoesNotReceiveGroundGlass() throws Exception {
        var cells=snapshot(compile(load("010719")),BlockPos.ORIGIN);
        var patch=com.formacraft.server.edit.WindowInsertCompiler.compile(cells,BlockPos.ORIGIN,Set.of(),2,2);
        assertEquals(4,patch.size(),"Use the intact wall without cutting its outer corner");
        // No ground glass or corner cutting is permitted.
        for(var b:patch) {assertTrue(b.dy()>=1);assertTrue(cells.get(new BlockPos(b.dx(),b.dy(),b.dz())).isOpaque());}
    }
    @Test void stiltRepairRestoresExplicitSevenBySevenAndWoodSupports() throws Exception {
        var live=snapshot(compile(load("011057")),BlockPos.ORIGIN);
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)for(int y=0;y<3;y++) {
            var state=live.get(new BlockPos(x,y,z));
            if((x==-3||x==0||x==3)&&(z==-3||z==0||z==3))assertTrue(state!=null&&state.isOf(net.minecraft.block.Blocks.OAK_LOG));
            else assertTrue(state==null||state.isAir(),"Underfloor void "+x+","+y+","+z);
        }
        assertTrue(live.get(new BlockPos(3,3,3)).isOpaque());
    }
    @Test void huizhouWingsCompileWithoutOverlappingCorners() throws Exception {
        var cells=snapshot(compile(load("011447")),BlockPos.ORIGIN);
        assertTrue(cells.values().stream().anyMatch(s->s.getBlock() instanceof net.minecraft.block.FenceBlock),"Lattice windows survive courtyard repair");
    }
    @Test void adobeRailingStaysInsideBodyAndAboveFloor() throws Exception {
        var cells=snapshot(compile(load("011629")),BlockPos.ORIGIN);int count=0;
        for(var e:cells.entrySet())if(e.getValue().getBlock() instanceof net.minecraft.block.FenceBlock) {
            count++;var p=e.getKey();assertTrue(Math.abs(p.getX())<6&&Math.abs(p.getZ())<6,"Railing must stay inside: "+p);
            assertEquals(5,p.getY());
        }
        assertTrue(count>=6);
        for(int x=-1;x<=0;x++)for(int y=1;y<=2;y++)
            assertTrue(air(cells,new BlockPos(x,y,-6)),"Central entrance must have its full width");
    }
    @Test void fortressCanGenerateWithSixRiseFlights() throws Exception {
        var cells=snapshot(compile(load("012049")),BlockPos.ORIGIN);
        var walk=new HashSet<BlockPos>();
        for(int x=-15;x<=14;x++)for(int z=-3;z<=3;z++) {
            var p=new BlockPos(x,0,z);var floor=cells.get(p);
            if(floor!=null&&!floor.isAir()&&air(cells,p.up())&&air(cells,p.up(2)))walk.add(p);
        }
        var reached=new HashSet<BlockPos>();var queue=new ArrayDeque<BlockPos>();queue.add(BlockPos.ORIGIN);reached.add(BlockPos.ORIGIN);
        while(!queue.isEmpty()) {var p=queue.remove();for(var n:List.of(p.north(),p.south(),p.east(),p.west()))
            if(walk.contains(n)&&reached.add(n))queue.add(n);}
        assertTrue(reached.contains(new BlockPos(-12,0,0)),"Interior west passage");
        assertTrue(reached.contains(new BlockPos(12,0,0)),"Interior east passage");
    }
    private boolean air(Map<BlockPos,net.minecraft.block.BlockState> cells,BlockPos p) {
        var state=cells.get(p);return state==null||state.isAir()||state.getBlock() instanceof net.minecraft.block.DoorBlock;
    }
}
