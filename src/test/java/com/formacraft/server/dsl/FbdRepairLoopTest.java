package com.formacraft.server.dsl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.formacraft.common.dsl.*;
import com.formacraft.common.llm.dto.*;
import com.formacraft.server.assembly.*;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.*;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class FbdRepairLoopTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String ROOT = "/components/0/params/fbd_program";
    static final String ROOF = ROOT + "/functions/0/body/1/params/type";
    static final String CALL = ROOT + "/main/0/body/0/body/0/fn";
    @BeforeAll static void bootstrap() { MinecraftRegistryTestBootstrap.initialize(); }
    @AfterEach void clear() { AssemblyCompileDiagnostics.clear(); }
    static ObjectNode fixture() throws Exception {
        return (ObjectNode)JSON.readTree(Files.readString(Path.of("src/main/resources/assets/formacraft/fbd/examples/repeated_pavilions.llmplan.json")));
    }
    static void put(ObjectNode tree, String path, JsonNode value) {
        ((ObjectNode)tree.at(path.substring(0,path.lastIndexOf('/')))).set(path.substring(path.lastIndexOf('/')+1),value);
    }
    static LlmPlan plan(ObjectNode tree) throws Exception { return JSON.treeToValue(tree,LlmPlan.class); }
    static FbdRepairLoop.Patch patch(String path, String value) { return new FbdRepairLoop.Patch(path,TextNode.valueOf(value)); }

    @Test void invalidEnumAndUnknownFunctionAreRepairedInTwoValidatedRoundsBeforeGeometry() throws Exception {
        var input=fixture(); put(input,ROOF,TextNode.valueOf("BANANA")); put(input,CALL,TextNode.valueOf("pavilon"));
        var original=plan(input); var before=JSON.valueToTree(original); var rounds=new ArrayList<String>();
        var result=FbdRepairLoop.run(original,(candidate,gap,round) -> {
            assertNotNull(gap.details().get("node")); assertNotNull(gap.details().get("stage"));
            rounds.add(gap.code());
            return CompletableFuture.completedFuture(round==1 ? patch(ROOF,"FLAT") : patch(CALL,"pavilion"));
        },phase -> {}).join();
        assertEquals(List.of("E_FBD_INVALID_ENUM","E_FBD_UNKNOWN_FUNCTION"),rounds);
        assertEquals(2,result.attempts()); assertFalse(result.plan().hasCapabilityGap());
        assertEquals(before,JSON.valueToTree(original));
        var output=JSON.valueToTree(result.plan());
        assertEquals(input.at(ROOT+"/seed"),output.at(ROOT+"/seed"));
        assertEquals(input.at(ROOT+"/main/0/times"),output.at(ROOT+"/main/0/times"));
        assertEquals(input.at("/components/0/dimensions"),output.at("/components/0/dimensions"));
        var compilation=FbdCompiler.compile(result.plan().components().getFirst().params().get("fbd_program"));
        var blocks=new MetaAssemblyEngine().executeGeometry(FbdAssemblyAdapter.spec(compilation),
                new MetaAssemblyEngine.Context(null,BlockPos.ORIGIN,Direction.SOUTH,null,new FbdExecutionBudget(FbdLimits.DEFAULT)));
        assertFalse(blocks.isEmpty());
    }

    @Test void numericTypeCorrectionKeepsTheExactDimensionAndReportsTheFieldPath() throws Exception {
        var input=fixture(); var height=ROOT+"/functions/0/body/0/params/h"; put(input,height,DoubleNode.valueOf(6.0));
        var original=plan(input); var gap=FbdRepairLoop.inspect(original);
        assertNotNull(gap);
        assertEquals("E_FBD_TYPE_MISMATCH",gap.code()); assertEquals(height,gap.path());
        assertThrows(IllegalArgumentException.class,()->FbdRepairLoop.applyPatch(original,gap,new FbdRepairLoop.Patch(height,IntNode.valueOf(7))));
        var result=FbdRepairLoop.run(original,(p,g,r)->CompletableFuture.completedFuture(new FbdRepairLoop.Patch(height,IntNode.valueOf(6))),phase->{}).join();
        assertFalse(result.plan().hasCapabilityGap()); assertEquals(1,result.attempts());
    }

    @Test void proposalCannotChangeSeedRepeatFramesEnvelopeOrOtherParameters() throws Exception {
        var input=fixture(); put(input,ROOF,TextNode.valueOf("BANANA")); var original=plan(input);
        var gap=FbdRepairLoop.inspect(original);
        for (String path : List.of(ROOT+"/seed",ROOT+"/main/0/times",ROOT+"/main/0/body/0/origin/1",
                "/components/0/dimensions/width",ROOT+"/functions/0/body/0/params/w")) {
            assertThrows(IllegalArgumentException.class,()->FbdRepairLoop.applyPatch(original,gap,new FbdRepairLoop.Patch(path,IntNode.valueOf(1))),path);
        }
        var calls=new AtomicInteger();
        var result=FbdRepairLoop.run(original,(p,g,r)->{calls.incrementAndGet();return CompletableFuture.completedFuture(new FbdRepairLoop.Patch(ROOT+"/seed",IntNode.valueOf(0)));},phase->{}).join();
        assertEquals(2,calls.get()); assertTrue(result.plan().hasCapabilityGap());
        assertEquals(JSON.valueToTree(original.components()),JSON.valueToTree(result.plan().components()));
        assertEquals("E_FBD_INVALID_ENUM",result.plan().capabilityGap().code());
    }

    @Test void budgetAndOutOfRangeRequirementsAreTerminalWithoutModelCalls() throws Exception {
        for (boolean budget : List.of(true,false)) {
            var input=fixture();
            put(input,budget?ROOT+"/main/0/times":ROOT+"/functions/0/body/0/params/h",IntNode.valueOf(budget?10000:4));
            var calls=new AtomicInteger();
            var result=FbdRepairLoop.run(plan(input),(p,g,r)->{calls.incrementAndGet();throw new AssertionError("terminal diagnostic");},phase->{}).join();
            assertEquals(0,calls.get()); assertEquals(0,result.attempts()); assertTrue(result.plan().hasCapabilityGap());
            assertEquals(budget?"E_FBD_BUDGET_EXCEEDED":"E_FBD_PARAM_OUT_OF_RANGE",result.plan().capabilityGap().code());
        }
    }

    @Test void providerFailureIsBoundedAndPreservesOriginalProgram() throws Exception {
        var input=fixture(); put(input,ROOF,TextNode.valueOf("BANANA")); var original=plan(input);
        var result=FbdRepairLoop.run(original,(p,g,r)->CompletableFuture.failedFuture(new TimeoutException()),phase->{}).join();
        assertEquals(2,result.attempts()); assertTrue(result.plan().hasCapabilityGap());
        assertEquals(original.components(),result.plan().components());
    }

    @Test void unknownDimensionVariableCannotBeReboundToAnotherDimension() throws Exception {
        var input=fixture(); put(input,ROOT+"/functions/0/body/0/params/h",TextNode.valueOf("$missing_height"));
        var result=FbdRepairLoop.run(plan(input),(p,g,r)->{throw new AssertionError("variable repair may change dimensions");},phase->{}).join();
        assertEquals(0,result.attempts()); assertEquals("E_FBD_UNKNOWN_VARIABLE",result.plan().capabilityGap().code());
    }

    @Test void ambiguousFunctionNameCannotBeGuessedOrRedirectedToAnotherBuilding() throws Exception {
        var input=fixture(); put(input,CALL,TextNode.valueOf("pavilio"));
        ((ArrayNode)input.at(ROOT+"/functions")).addObject().put("name","pavilioz")
                .set("params",JSON.createArrayNode());
        ((ObjectNode)input.at(ROOT+"/functions/1")).set("body",JSON.createArrayNode());
        var original=plan(input);
        var result=FbdRepairLoop.run(original,(p,g,r)->CompletableFuture.completedFuture(patch(CALL,"pavilion")),phase->{}).join();
        assertTrue(result.plan().hasCapabilityGap()); assertEquals(2,result.attempts());
        assertEquals(original.components(),result.plan().components());
    }

    @Test void validBaselineNeedsNoRepairAndDoesNotLeakCompileDiagnostics() throws Exception {
        var previous=new CapabilityGap("existing","existing","",List.of()); AssemblyCompileDiagnostics.set(previous);
        var input=plan(fixture());
        var result=FbdRepairLoop.run(input,(p,g,r)->{throw new AssertionError("valid baseline");},phase->{}).join();
        assertSame(input,result.plan()); assertEquals(0,result.attempts()); assertSame(previous,AssemblyCompileDiagnostics.get());
    }

    @Test void capturedGameBaselineNeverCallsRepairOrChangesThePrograms() throws Exception {
        var manifest=JSON.readTree(Files.readString(Path.of("src/test/resources/game_replays/fbd_accepted_baseline_manifest.json")));
        var bytes=Files.readString(Path.of("src/test/resources/game_replays/fbd_20261024_patch_merge.json"))
                .replace("\r\n","\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(manifest.at("/fixtures/0/sha256").asText(),
                HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        var snapshots=JSON.readTree(Files.readString(Path.of("src/test/resources/game_replays/fbd_20261024_patch_merge.json")));
        assertEquals(5,snapshots.size());
        for (var snapshot : snapshots) {
            var original=JSON.treeToValue(snapshot,LlmPlan.class);
            var result=FbdRepairLoop.run(original,(p,g,r)->{throw new AssertionError("baseline changed");},phase->{}).join();
            assertSame(original,result.plan()); assertEquals(0,result.attempts());
        }
    }
}
