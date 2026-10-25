package com.formacraft.server.build.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.formacraft.common.build.*;
import com.formacraft.common.model.build.*;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GeometryDiagnosticsTest {
    @BeforeAll static void bootstrap() { MinecraftRegistryTestBootstrap.initialize(); }
    private PlannedBlock block(int x, int y, int z, boolean air) {
        return new PlannedBlock(new BlockPos(x,y,z), (air ? Blocks.AIR : Blocks.STONE).getDefaultState());
    }
    private BuildQualityReport check(List<PlannedBlock> blocks, BuildingSpec spec) {
        return GradedQualityChecker.checkStructure(new GeneratedStructure(null, BlockPos.ORIGIN, "test", blocks), spec, null);
    }

    @Test void finalWritesAndAirDoNotInflateBlockCountsOrDimensions() {
        var spec = new BuildingSpec();
        var footprint = new Footprint(); footprint.setWidth(1); footprint.setDepth(1);
        spec.setFootprint(footprint); spec.setHeight(1);
        var report = check(List.of(block(0,0,0,false), block(0,0,0,false),
                block(100,100,100,false), block(100,100,100,true)), spec);
        assertEquals(1, report.stats().totalBlocks);
        assertEquals(2, report.stats().duplicatePositions);
        assertFalse(report.issues().stream().anyMatch(i -> i.code().startsWith("DIM_")));
        var duplicates = report.issues().stream().filter(i -> i.code().equals("DUPLICATE_POS")).findFirst().orElseThrow();
        assertEquals(2, duplicates.affectedCount());
        assertEquals(new BuildQualityIssue.Position(100,100,100), duplicates.positions().get(1));
    }

    @Test void malformedBlocksProduceFatalDiagnosticInsteadOfDereferenceFailure() {
        for (var invalid : List.of(new PlannedBlock(null, Blocks.STONE.getDefaultState()),
                                  new PlannedBlock(BlockPos.ORIGIN, null))) {
            var report = check(List.of(invalid), null);
            assertTrue(report.hasFatal());
            assertEquals("ILLEGAL_BLOCK", report.activeDiagnostics().getFirst().code());
            assertEquals(1, report.activeDiagnostics().getFirst().affectedCount());
        }
    }

    @Test void selectionEvidenceUsesFinalPositionsAndDoesNotLeakRequestContext() {
        var req = new com.formacraft.common.model.request.FormaRequest();
        req.setSelectionMin(BlockPos.ORIGIN); req.setSelectionMax(new BlockPos(1,2,2));
        var blocks = new ArrayList<PlannedBlock>();
        blocks.add(block(0,0,0,false));
        for (int x=2;x<20;x++) blocks.add(block(x,0,0,false));
        var report = com.formacraft.server.build.BuildConstraintContext.withRequest(req, () -> check(blocks, null));
        assertNull(com.formacraft.server.build.BuildConstraintContext.current());
        var issue = report.activeDiagnostics().stream().filter(i -> i.code().equals("CONSTRAINT_OOB")).findFirst().orElseThrow();
        assertEquals(18, issue.affectedCount());
        assertEquals(8, issue.positions().size());
        assertEquals(new BuildQualityIssue.Position(2,0,0), issue.positions().getFirst());
        assertEquals(BuildQualitySeverity.ERROR, issue.severity());
    }

    @Test void fullyErasedGeometryCannotMasqueradeAsValidStructure() {
        var report = check(List.of(block(0,0,0,false), block(0,0,0,true)), null);
        assertTrue(report.hasFatal());
        assertTrue(report.issues().stream().anyMatch(i -> i.code().equals("STRUCT_NO_SOLID")));
    }

    @Test void floatingEvidenceIsBoundedDeterministicAndJsonSafe() throws Exception {
        var blocks = new ArrayList<PlannedBlock>();
        for (int x=19;x>=0;x--) blocks.add(block(x,5,0,false));
        var report = new BuildQualityReport();
        GradedQualityChecker.checkSupport(blocks, p -> Blocks.AIR.getDefaultState(), report);
        report.phase("final");
        var issue = report.activeDiagnostics().getFirst();
        assertEquals("FLOATING", issue.code());
        assertEquals(BuildQualitySeverity.ERROR, issue.severity());
        assertEquals(20, issue.affectedCount());
        assertEquals(8, issue.positions().size());
        assertEquals(new BuildQualityIssue.Position(0,5,0), issue.positions().getFirst());
        var json = new ObjectMapper();
        assertEquals(issue, json.readValue(json.writeValueAsString(issue), BuildQualityIssue.class));
        assertThrows(UnsupportedOperationException.class, () -> issue.positions().clear());
    }

    @Test void repairedSupportClearsActiveEvidenceButHistoryKeepsOriginalLocation() {
        var blocks = new ArrayList<PlannedBlock>();
        for (int x=0;x<12;x++) blocks.add(block(x,5,0,false));
        var initial = new BuildQualityReport();
        GradedQualityChecker.checkSupport(blocks, p -> Blocks.AIR.getDefaultState(), initial);
        initial.phase("initial");
        var history = initial.activeDiagnostics().getFirst().asHistory();
        var delivered = new BuildQualityReport();
        GradedQualityChecker.checkSupport(blocks, p -> Blocks.STONE.getDefaultState(), delivered);
        delivered.phase("final"); delivered.add(history);
        assertTrue(delivered.recommendApply());
        assertTrue(delivered.activeDiagnostics().isEmpty());
        assertEquals("initial", history.phase());
        assertEquals(12, history.affectedCount());
        assertEquals(8, history.positions().size());
    }
}
