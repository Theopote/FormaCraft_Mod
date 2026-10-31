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
/** Stilts height + glass incremental patch contracts (from live-2323 fixtures). */
class StiltAndGlassPatchRegressionTest {
    @BeforeAll static void init() { MinecraftRegistryTestBootstrap.initialize(); }
    @AfterEach void clear() { AssemblyCompileDiagnostics.clear(); }
    private LlmPlan load(String name) throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
        try(var in=getClass().getResourceAsStream("/regressions/live-2323-"+name+".json")) {return mapper.readValue(in,LlmPlan.class);}
    }
    private List<com.formacraft.common.patch.BlockPatch> compile(LlmPlan p) {
        var blocks=ComponentPlanCompiler.compile(p,BlockPos.ORIGIN,null,null,false);
        assertNull(AssemblyCompileDiagnostics.get()); assertFalse(blocks.isEmpty()); return blocks;
    }
    @Test void fourHighStiltsReachCabinBaseWithoutHeightCap() throws Exception {
        var p=load("stilt"); assertTrue(LlmPlanTerrainBounds.hasAuthoredSupports(p));
        var built=compile(p);
        int floor=p.layout().slots().getFirst().anchor().y()+4;
        assertFalse(built.stream().anyMatch(b->b.dy()<floor && b.targetBlock().contains("cobblestone")));
        for(var c:p.components()) if(c.params()!=null && c.params().containsKey("supports")) {
            var blocks=new DecorDetailGenerator().generate(new SemanticComponent("DECOR_DETAIL",null,c));
            assertEquals(4,blocks.size());
            assertEquals(3,blocks.stream().mapToInt(b->b.dy()).max().orElseThrow());
            assertTrue(blocks.stream().allMatch(b->b.targetBlock().contains("oak_log")));
        }
    }
    @Test void glassQueryNormalizesToBoundedPatch() throws Exception {
        var c=load("patch").components().getFirst();
        var fixed=IncrementalWindowQuery.normalize(c);
        assertTrue(IncrementalPrimitivePolicy.allows(fixed));
        assertEquals(c.relativePosition(),fixed.relativePosition());
        assertEquals(c.dimensions(),fixed.dimensions());
        var method=com.formacraft.server.network.LlmPlanPreviewBuilder.class
                .getDeclaredMethod("sanitizeIncrementalPatchPlan",LlmPlan.class);
        method.setAccessible(true);
        var sanitized=(LlmPlan)method.invoke(null,load("patch"));
        var blocks=compile(sanitized);
        assertEquals(4,blocks.size());
        assertTrue(blocks.stream().allMatch(b->b.targetBlock().contains("glass")));
    }
    @Test void woodLatticeSurvivesFullCompiler() throws Exception {
        var blocks=compile(load("hui"));
        assertTrue(blocks.stream().anyMatch(b->b.targetBlock().contains("_fence")),"Actual lattice must be wood fences");
    }
    @Test void openPavilionsHaveActualAirOnAllSides() throws Exception {
        var p=load("pavilions");var blocks=compile(p);
        var cells=new HashMap<BlockPos,String>();
        for(var b:blocks) cells.put(new BlockPos(b.dx(),b.dy(),b.dz()),b.targetBlock());
        for(var c:p.components()) if("MASS_MAIN".equals(c.componentType())) {
            var slot=p.layout().slots().stream().filter(s->s.slotId().equals(c.slotId())).findFirst().orElseThrow();
            var a=slot.anchor();
            for(int[] offset:new int[][]{{0,-2},{0,2},{-2,0},{2,0}})
                assertEquals("minecraft:air",cells.get(new BlockPos(a.x()+offset[0],a.y()+1,a.z()+offset[1])));
        }
    }
    @Test void adobeGuardIsOutsideArrivalAndSurvivesCompilation() throws Exception {
        var p=load("adobe");var blocks=compile(p);
        var cells=new HashMap<BlockPos,String>();
        for(var b:blocks) cells.put(new BlockPos(b.dx(),b.dy(),b.dz()),b.targetBlock());
        var slot=p.layout().slots().stream().filter(s->s.slotId().equals("adobe_house")).findFirst().orElseThrow().anchor();
        for(int x:new int[]{7,11}) for(int z=4;z<=9;z++)
            assertEquals("minecraft:dark_oak_fence",cells.get(new BlockPos(slot.x()+x,slot.y()+5,slot.z()+z)));
    }
    @Test void fortressGeometryAfterBackendEntranceDelegationStillCompiles() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(load("fortress"));
        tree.remove(List.of("plan_status","error","capability_gap"));
        for(var c:tree.path("components")) if("MASS_MAIN".equals(c.path("component_type").asText())) {
            var p=(com.fasterxml.jackson.databind.node.ObjectNode)c.path("params");
            p.put("entrance_type","door");p.put("suppress_doors",true);
        }
        var result=compile(mapper.treeToValue(tree,LlmPlan.class));
        assertTrue(result.stream().anyMatch(b->b.dy()==9 && b.targetBlock().contains("blackstone_stairs")),
                "Stair treads must reach the authored tower-platform level");
        for(int side:new int[]{-1,1}) assertTrue(result.stream().anyMatch(b->b.dy()==9
                && b.dx()*side>0 && b.targetBlock().contains("blackstone_stairs")),"Both towers must reach the deck");
    }
}
