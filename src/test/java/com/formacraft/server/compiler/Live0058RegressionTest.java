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
class Live0058RegressionTest {
    @BeforeAll static void init() { MinecraftRegistryTestBootstrap.initialize(); }
    @AfterEach void clear() { AssemblyCompileDiagnostics.clear(); }
    private LlmPlan load(String name) throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
        try(var in=getClass().getResourceAsStream("/regressions/live-0058-"+name+".json")) {return mapper.readValue(in,LlmPlan.class);}
    }
    private List<com.formacraft.common.patch.BlockPatch> compile(LlmPlan p) {
        var blocks=ComponentPlanCompiler.compile(p,BlockPos.ORIGIN,null,null,false);
        assertNull(AssemblyCompileDiagnostics.get()); assertFalse(blocks.isEmpty()); return blocks;
    }
    @Test void facadeWindowPatchCompilesToExactlyFourGlassCells() throws Exception {
        var method=com.formacraft.server.network.LlmPlanPreviewBuilder.class.getDeclaredMethod("sanitizeIncrementalPatchPlan",LlmPlan.class);
        method.setAccessible(true);
        var blocks=compile((LlmPlan)method.invoke(null,load("window-add")));
        assertEquals(4,blocks.size());
        assertTrue(blocks.stream().allMatch(b->b.targetBlock().contains("glass")));
    }
    @Test void wholeBuildingRemovalResponseStillRejected() throws Exception {
        var method=com.formacraft.server.network.LlmPlanPreviewBuilder.class.getDeclaredMethod("isIncrementalComponentPatch",LlmPlan.class);
        method.setAccessible(true);
        assertEquals(false,method.invoke(null,load("window-remove")));
    }
    @Test void actualHuizhouPlanHasSealableInwardLattice() throws Exception {
        var blocks=compile(load("hui"));
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
            assertEquals("minecraft:white_terracotta",b.targetBlock());
        }
    }
    @Test void oneLayerPavilionsKeepOpenSpaceAndRoofAboveColumns() throws Exception {
        assertOpenPavilions(load("pavilions"));
    }
    @Test void roofBeforeDeckHasSameColumnAttachment() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(load("pavilions"));
        var original=tree.withArray("components");
        var reversed=mapper.createArrayNode();
        for(int i=original.size()-1;i>=0;i--) reversed.add(original.get(i));
        tree.set("components",reversed);
        assertOpenPavilions(mapper.treeToValue(tree,LlmPlan.class));
    }
    private void assertOpenPavilions(LlmPlan p) {
        var blocks=compile(p);
        var cells=new HashMap<BlockPos,String>();
        for(var b:blocks) cells.put(new BlockPos(b.dx(),b.dy(),b.dz()),b.targetBlock());
        for(var c:p.components()) if("MASS_MAIN".equals(c.componentType())) {
            var a=p.layout().slots().stream().filter(t->t.slotId().equals(c.slotId())).findFirst().orElseThrow().anchor();
            assertTrue(cells.getOrDefault(new BlockPos(a.x(),a.y()+1,a.z()),"minecraft:air").equals("minecraft:air"));
            assertTrue(blocks.stream().anyMatch(b->b.dy()>=a.y()+4 && Math.abs(b.dx()-a.x())<=3 && Math.abs(b.dz()-a.z())<=3 && b.targetBlock().contains("deepslate")),"Roof must rest on the column tops");
        }
    }
}
