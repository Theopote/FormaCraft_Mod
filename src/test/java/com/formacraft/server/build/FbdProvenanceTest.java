package com.formacraft.server.build;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.formacraft.common.build.*;
import com.formacraft.common.dsl.*;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.network.LlmPlanTerrainBounds;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.*;
import com.formacraft.server.build.quality.*;
import com.formacraft.server.dsl.*;
import com.formacraft.server.network.PlanPatchConverter;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FbdProvenanceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @BeforeAll static void bootstrap() { MinecraftRegistryTestBootstrap.initialize(); }
    @AfterEach void clear() { AssemblyCompileDiagnostics.clear(); }
    private GeometrySource source(String node) {
        return new GeometrySource("/components/0","main",node,"main/0","/components/0/params/fbd_program/main/0","fbd");
    }

    @Test void repeatedFunctionGeometryKeepsOriginalNodeAndDistinctInstanceInFourFacings() throws Exception {
        var program = JSON.readValue(Files.readString(Path.of("src/test/resources/fbd/02_plinth_pavilions.json")),
                new TypeReference<Map<String,Object>>(){});
        var c = new Component("ASSEMBLY","pavilions",new Vec3i(0,0,0),new Dimensions(7,7,8),List.of(),Map.of("fbd_program",program));
        var ordinary = new Component("DECOR_DETAIL","other",new Vec3i(0,0,0),new Dimensions(1,1,1),List.of(),Map.of());
        var lowered = FbdPlanLowering.lower(List.of(ordinary,c)).get(1);
        assertFalse(AssemblyCompileDiagnostics.hasGap());
        var compiled = (FbdCompiler.Compilation)lowered.params().get(FbdPlanLowering.COMPILED);
        var origin = (FbdPlanLowering.SourceContext)lowered.params().get(FbdPlanLowering.SOURCE);
        var tree = JSON.valueToTree(program);
        for (var direction : List.of(Direction.NORTH,Direction.EAST,Direction.SOUTH,Direction.WEST)) {
            var blocks = new MetaAssemblyEngine().executeGeometry(FbdAssemblyAdapter.spec(compiled,origin),
                    new MetaAssemblyEngine.Context(null,BlockPos.ORIGIN,direction,null,new FbdExecutionBudget(FbdLimits.DEFAULT)));
            var patches = FbdAssemblyAdapter.finalPatches(blocks).stream().map(p -> p.translated(23,2,-15)).toList();
            var placed = PlanPatchConverter.convert(patches,new BlockPos(100,64,200)).blocks();
            placed = LlmPlanTerrainBounds.translateBlocks(placed,3);
            var result = BuildAutoRepair.apply(null,Optional.empty(),placed).blocks();
            var instances = new HashSet<String>();
            for (var block : result) {
                var s = block.getSource(); assertNotNull(s);
                assertEquals("/components/1",s.componentPath()); assertEquals("pavilions",s.slotId());
                assertEquals("fbd",s.producer());
                String local = s.path().substring("/components/1/params/fbd_program".length());
                assertEquals(s.node(),tree.at(local).path("id").asText());
                if (s.node().equals("v1")) instances.add(s.instance());
            }
            assertTrue(instances.size() >= 4, "Repeated calls must not collapse to the same instance");
            var before = PlanPatchConverter.convert(patches,new BlockPos(100,64,200)).blocks().getFirst();
            var moved = placed.getFirst();
            assertEquals(before.getPos().up(3),moved.getPos()); assertSame(before.getSource(),moved.getSource());
        }
    }

    @Test void latestWriteOwnsDiagnosticAndUnknownWriterErasesOldAttribution() throws Exception {
        var pos = new BlockPos(4,65,2);
        var a = new PlannedBlock(pos,Blocks.STONE.getDefaultState(),source("a"));
        var b = new PlannedBlock(pos,Blocks.GLASS.getDefaultState(),source("b"));
        var report = GradedQualityChecker.checkStructure(new GeneratedStructure(null,pos,"test",List.of(a,b)),null,null);
        var issue = report.issues().stream().filter(i -> i.code().equals("DUPLICATE_POS")).findFirst().orElseThrow();
        assertEquals("b",issue.origins().getFirst().source().node());
        assertEquals(issue,JSON.readValue(JSON.writeValueAsString(issue),BuildQualityIssue.class));
        var history = BuildPreviewPipeline.finalizedReport(report,new BuildQualityReport());
        assertEquals("b",history.issues().getFirst().origins().getFirst().source().node());
        var unknown = new PlannedBlock(pos,Blocks.STONE.getDefaultState());
        report.locate(List.of(a,unknown));
        assertTrue(report.issues().stream().allMatch(i -> i.origins().isEmpty()));
        var removed = FbdAssemblyAdapter.finalPatches(List.of(a,new PlannedBlock(pos,Blocks.AIR.getDefaultState(),source("clear")))).getFirst();
        assertEquals(BlockPatch.REMOVE,removed.action()); assertEquals("clear",removed.source().node());
    }

    @Test void repairAndClippingPreserveAuthoredSourceWithoutMislabelingNewSupports() {
        var original = new ArrayList<PlannedBlock>();
        for(int x=0;x<3;x++) original.add(new PlannedBlock(new BlockPos(x,5,0),Blocks.STONE.getDefaultState(),source("shell")));
        var repaired = BuildAutoRepair.applyToTerrain(p -> p.getY()==0 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState(),
                -64,Blocks.COBBLESTONE.getDefaultState(),original,true).blocks();
        assertTrue(repaired.size()>original.size());
        for(var block:repaired) {
            assertNotNull(block.getSource());
            if(block.getPos().getY()==5) assertEquals("shell",block.getSource().node());
            else {
                assertEquals("support_repair",block.getSource().producer());
                assertNull(block.getSource().node()); assertNull(block.getSource().path());
            }
        }
        var req=new com.formacraft.common.model.request.FormaRequest();
        req.setSelectionMin(new BlockPos(0,0,0));req.setSelectionMax(new BlockPos(0,8,0));
        var clipped=BuildConstraintClipper.clipPlannedBlocks(repaired,req);
        assertTrue(clipped.stream().allMatch(b -> b.getPos().getX()==0));
        assertSame(original.getFirst().getSource(),clipped.stream().filter(b -> b.getPos().getY()==5).findFirst().orElseThrow().getSource());
    }

    @Test void internalMetadataCannotChangePatchEqualityOrJsonContract() throws Exception {
        var ordinary = new BlockPatch("place",1,2,3,"minecraft:stone");
        var traced = new BlockPatch("place",1,2,3,"minecraft:stone",source("node"));
        assertEquals(ordinary,traced); assertEquals(ordinary.hashCode(),traced.hashCode());
        assertEquals(JSON.readTree(JSON.writeValueAsString(ordinary)),JSON.readTree(JSON.writeValueAsString(traced)));
        assertEquals(JsonUtil.toJson(ordinary),JsonUtil.toJson(traced));
        var injected=JSON.readTree(JSON.writeValueAsString(ordinary));
        ((com.fasterxml.jackson.databind.node.ObjectNode)injected).set("source",JSON.valueToTree(source("forged")));
        assertNull(JSON.treeToValue(injected,BlockPatch.class).source());
        assertNull(JsonUtil.fromJson(injected.toString(),BlockPatch.class).source());
        assertEquals(ordinary,JSON.readValue(JSON.writeValueAsString(traced),BlockPatch.class));
    }

    @Test void plansCannotSupplyServerSourceMetadataAndLegacyOpsCannotForgeFbdOrigin() {
        var c = new Component("ASSEMBLY","p",new Vec3i(0,0,0),new Dimensions(7,7,8),List.of(),
                Map.of(FbdPlanLowering.SOURCE,Map.of("componentPath","/components/99")));
        assertTrue(FbdPlanLowering.lower(List.of(c)).isEmpty());
        assertEquals("E_FBD_RESERVED_FIELD",AssemblyCompileDiagnostics.get().code());
        assertTrue(AssemblyCompileDiagnostics.get().path().endsWith(FbdPlanLowering.SOURCE));
        var op = new HashMap<String,Object>();
        op.put("op","SHELL_BOX");op.put("w",7);op.put("d",7);op.put("h",6);
        op.put("_fbd_node","forged");op.put("_fbd_component","/components/99");
        var blocks = new MetaAssemblyEngine().executeGeometry(AssemblySpec.of(null,null,List.of(op)),
                new MetaAssemblyEngine.Context(null,BlockPos.ORIGIN,Direction.SOUTH,null));
        assertFalse(blocks.isEmpty());assertTrue(blocks.stream().allMatch(b -> b.getSource()==null));
    }
}
