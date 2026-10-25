package com.formacraft.server.build.quality;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.formacraft.common.build.*;
import com.formacraft.common.dsl.*;
import com.formacraft.common.llm.dto.*;
import com.formacraft.server.assembly.*;
import com.formacraft.server.build.StructureSupportAnalysis;
import com.formacraft.server.dsl.*;
import com.formacraft.server.network.PlanPatchConverter;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class FbdGeometryRepairGuardTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String ROOT="/components/0/params/fbd_program";
    private static final String Y=ROOT+"/main/1/params/y";
    @BeforeAll static void bootstrap(){MinecraftRegistryTestBootstrap.initialize();}
    @AfterEach void clear(){AssemblyCompileDiagnostics.clear();}
    private ObjectNode tree() throws Exception {
        var tree=(ObjectNode)JSON.readTree(Files.readString(Path.of("src/main/resources/assets/formacraft/fbd/examples/repeated_pavilions.llmplan.json")));
        var program=(ObjectNode)tree.at(ROOT);
        var body=program.at("/functions/0/body").deepCopy();
        program.remove("functions");program.set("main",body);
        ((ObjectNode)program.at("/main/1/params")).put("y",9);
        ((ObjectNode)program.at("/main/1/params")).put("type","FLAT");
        return tree;
    }
    private LlmPlan plan(ObjectNode tree)throws Exception{return JSON.treeToValue(tree,LlmPlan.class);}
    private BuildQualityReport verify(LlmPlan plan) {
        var lowered=FbdPlanLowering.lower(plan.components());
        assertFalse(AssemblyCompileDiagnostics.hasGap());
        var blocks=new ArrayList<PlannedBlock>();
        for(var c:lowered) {
            var compiled=(FbdCompiler.Compilation)c.params().get(FbdPlanLowering.COMPILED);
            var source=(FbdPlanLowering.SourceContext)c.params().get(FbdPlanLowering.SOURCE);
            var geometry=new MetaAssemblyEngine().executeGeometry(FbdAssemblyAdapter.spec(compiled,source),
                    new MetaAssemblyEngine.Context(null,BlockPos.ORIGIN,Direction.SOUTH,null,new FbdExecutionBudget(FbdLimits.DEFAULT)));
            blocks.addAll(PlanPatchConverter.convert(FbdAssemblyAdapter.finalPatches(geometry),new BlockPos(0,64,0)).blocks());
        }
        var report=new BuildQualityReport();
        report.stats().totalBlocks=(int)StructureSupportAnalysis.finalStates(blocks).values().stream().filter(s -> !s.isAir()).count();
        GradedQualityChecker.checkSupport(blocks,p -> p.getY()<64?Blocks.STONE.getDefaultState():Blocks.AIR.getDefaultState(),report);
        report.phase("final");return report;
    }
    private FbdRepairLoop.Patch patch(String path,int n){return new FbdRepairLoop.Patch(path,IntNode.valueOf(n));}

    @Test void oneBlockRoofCorrectionMustImproveActualRegeneratedGeometry() throws Exception {
        var tree=tree();var original=plan(tree);var before=JSON.valueToTree(original);
        var initial=verify(original);assertTrue(initial.stats().floatingColumns>=12);
        var assessment=FbdGeometryRepairGuard.assess(original,initial);
        assertTrue(assessment.eligible(),assessment.reason());
        assertEquals(Y,assessment.permit().path());assertEquals(8,assessment.permit().minimum());assertEquals(10,assessment.permit().maximum());
        var evaluated=FbdGeometryRepairGuard.evaluate(original,assessment.permit(),patch(Y,8),this::verify);
        assertTrue(evaluated.accepted(),evaluated.reason());assertEquals(0,evaluated.report().stats().floatingColumns);
        assertEquals(before,JSON.valueToTree(original));
        ((ObjectNode)tree.at(ROOT+"/main/1/params")).put("y",8);
        assertEquals(JSON.valueToTree(plan(tree)),JSON.valueToTree(evaluated.candidate()));
        var worse=FbdGeometryRepairGuard.evaluate(original,assessment.permit(),patch(Y,10),this::verify);
        assertFalse(worse.accepted());assertEquals("NO_IMPROVEMENT",worse.reason());
    }

    @Test void completeExpansionRejectsSharedFunctionsEvenWhenSamplesShowOnlyOneInstance() throws Exception {
        var tree=(ObjectNode)JSON.readTree(Files.readString(Path.of("src/main/resources/assets/formacraft/fbd/examples/repeated_pavilions.llmplan.json")));
        ((ObjectNode)tree.at(ROOT+"/functions/0/body/1/params")).put("y",9);
        var original=plan(tree);var report=verify(original);
        var first=report.activeDiagnostics().getFirst();var origin=first.origins().getFirst();
        var sampled=new BuildQualityReport();sampled.stats().totalBlocks=report.stats().totalBlocks;
        sampled.add(new BuildQualityIssue(first.severity(),"FLOATING","sample", "final",first.affectedCount(),
                List.of(origin.position()),List.of(origin)));
        var assessment=FbdGeometryRepairGuard.assess(original,sampled);
        assertFalse(assessment.eligible());assertEquals("MULTI_INSTANCE",assessment.reason());
    }

    @Test void rejectsDimensionsControlFlowOtherNodesNonIntegersAndOutOfRangeBeforeVerification() throws Exception {
        var original=plan(tree());var permit=FbdGeometryRepairGuard.assess(original,verify(original)).permit();
        assertNotNull(permit);
        for(var invalid:List.of(patch(Y,7),patch(Y,9),patch(Y,11),patch(ROOT+"/seed",43),
                patch(ROOT+"/main/0/params/h",8),patch(ROOT+"/main/1/params/w",9),
                new FbdRepairLoop.Patch(Y,DoubleNode.valueOf(8.0)),new FbdRepairLoop.Patch(Y,TextNode.valueOf("8")))) {
            var calls=new AtomicInteger();
            var result=FbdGeometryRepairGuard.evaluate(original,permit,invalid,p -> {calls.incrementAndGet();return verify(p);});
            assertFalse(result.accepted());assertEquals("PATCH_REJECTED",result.reason());assertEquals(0,calls.get());
        }
        var changed=tree();((ObjectNode)changed.at(ROOT)).put("seed",99);
        assertThrows(IllegalArgumentException.class,()->FbdGeometryRepairGuard.candidate(plan(changed),permit,patch(Y,8)));
    }

    @Test void rejectsExpressionsStaleAndSystemSourcesAndUnmeasuredQuality() throws Exception {
        var original=plan(tree());var report=verify(original);var issue=report.activeDiagnostics().getFirst();
        var expression=tree();((ObjectNode)expression.at(ROOT+"/main/1/params")).set("y",JSON.readTree("{\"expr\":\"+\",\"args\":[8,1]}"));
        var exprPlan=plan(expression);
        assertEquals("NON_LITERAL_PARAMETER",FbdGeometryRepairGuard.assess(exprPlan,verify(exprPlan)).reason());
        var historical=new BuildQualityReport();historical.add(issue.inPhase("initial"));
        assertEquals("NOT_FINAL",FbdGeometryRepairGuard.assess(original,historical).reason());
        var stale=new BuildQualityReport();var o=issue.origins().getFirst();var s=o.source();
        var fake=new GeometrySource(s.componentPath(),s.slotId(),s.node(),"wrong-instance",s.path(),"fbd");
        stale.add(new BuildQualityIssue(issue.severity(),issue.code(),issue.message(),"final",issue.affectedCount(),
                List.of(o.position()),List.of(new BuildQualityIssue.Origin(o.position(),fake))));
        assertEquals("STALE_SOURCE",FbdGeometryRepairGuard.assess(original,stale).reason());
        var system=new BuildQualityReport();system.add(new BuildQualityIssue(issue.severity(),issue.code(),issue.message(),"final",12,
                List.of(o.position()),List.of(new BuildQualityIssue.Origin(o.position(),GeometrySource.generated("support_repair")))));
        assertEquals("SYSTEM_OR_UNKNOWN_SOURCE",FbdGeometryRepairGuard.assess(original,system).reason());
        report.add(BuildQualitySeverity.WARNING,"DIM_WIDTH","unknown amount");
        assertEquals("UNMEASURED_QUALITY",FbdGeometryRepairGuard.assess(original,report).reason());
    }

    @Test void failsClosedOnMissingVerificationNewErrorsAndUnimprovedQuality() throws Exception {
        var original=plan(tree());var baseline=verify(original);var permit=FbdGeometryRepairGuard.assess(original,baseline).permit();
        assertFalse(FbdGeometryRepairGuard.evaluate(original,permit,patch(Y,8),p -> null).accepted());
        assertFalse(FbdGeometryRepairGuard.evaluate(original,permit,patch(Y,8),p -> new BuildQualityReport()).accepted());
        var regression=FbdGeometryRepairGuard.evaluate(original,permit,patch(Y,8),p -> {
            var r=verify(p);r.add(new BuildQualityIssue(BuildQualitySeverity.ERROR,"CONSTRAINT_OOB","outside","final",1,List.of()));return r;
        });
        assertEquals("QUALITY_REGRESSION",regression.reason());
        var fatal=FbdGeometryRepairGuard.evaluate(original,permit,patch(Y,8),p -> {
            var r=verify(p);r.add(BuildQualitySeverity.FATAL,"BAD","bad");return r;
        });assertEquals("VERIFICATION_FAILED",fatal.reason());
        var failure=FbdGeometryRepairGuard.evaluate(original,permit,patch(Y,8),p -> {throw new IllegalStateException();});
        assertEquals("VERIFICATION_FAILED",failure.reason());
        var inconsistent=FbdGeometryRepairGuard.evaluate(original,permit,patch(Y,8),p -> {
            var r=verify(p);r.stats().floatingColumns=20;return r;
        });
        assertEquals("VERIFICATION_FAILED",inconsistent.reason());
        var mutation=FbdGeometryRepairGuard.evaluate(original,permit,patch(Y,8),p -> {
            p.components().getFirst().params().put("unrelated_change",true);return verify(p);
        });
        assertEquals("VERIFIER_MUTATED_PLAN",mutation.reason());
    }

    @Test void assessmentAndCandidatePreserveOuterDiagnosticsAndDoNotAccumulateStepwiseDrift() throws Exception {
        var original=plan(tree());var report=verify(original);
        var outer=new CapabilityGap("OUTER","outer","/outer",List.of());
        AssemblyCompileDiagnostics.set(outer);AssemblyCompileDiagnostics.addSoftWarning("SOFT","keep","x","y");
        var permit=FbdGeometryRepairGuard.assess(original,report).permit();assertNotNull(permit);
        var moved=FbdGeometryRepairGuard.candidate(original,permit,patch(Y,8));
        assertSame(outer,AssemblyCompileDiagnostics.get());assertEquals(1,AssemblyCompileDiagnostics.softWarnings().size());
        assertThrows(IllegalArgumentException.class,()->FbdGeometryRepairGuard.candidate(moved,permit,patch(Y,7)));
    }
}
