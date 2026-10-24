package com.formacraft.server.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.dsl.*;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.patch.PatchExecutor;
import com.formacraft.common.world.BlockSnapshot;
import com.formacraft.server.assembly.*;
import com.formacraft.server.build.quality.*;
import com.formacraft.server.dsl.*;
import com.formacraft.test.*;
import net.minecraft.block.*;
import net.minecraft.util.math.*;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class StructureSupportAnalysisTest {
    @BeforeAll static void bootstrap() { MinecraftRegistryTestBootstrap.initialize(); }
    @AfterEach void clear() { PlayerMutationLedger.get().clearAll(); AssemblyCompileDiagnostics.clear(); }
    private PlannedBlock stone(int x, int y, int z) { return new PlannedBlock(new BlockPos(x,y,z), Blocks.STONE.getDefaultState()); }
    private List<PlannedBlock> floor(int y) {
        var blocks = new ArrayList<PlannedBlock>();
        for (int x=0;x<4;x++) for (int z=0;z<4;z++) blocks.add(stone(x,y,z));
        return blocks;
    }

    @Test void realPavilionsNeedNoPillarForestOnFlatOrSlopingGroundInFourFacings() throws Exception {
        var responses = new ObjectMapper().readTree(Files.readString(Path.of("src/test/resources/game_replays/fbd_20261024_patch_merge.json")));
        assertEquals(5, responses.size());
        for (var response : responses) for (var facing : List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST)) {
            var plan = LlmPlanParser.parse(response.toString());
            AssemblyCompileDiagnostics.clear();
            var lowered = FbdPlanLowering.lower(plan.components());
            assertFalse(AssemblyCompileDiagnostics.hasGap());
            var blocks = new ArrayList<PlannedBlock>();
            for (var component : lowered) {
                var compiled = (FbdCompiler.Compilation)component.params().get(FbdPlanLowering.COMPILED);
                var p = component.relativePosition();
                blocks.addAll(new MetaAssemblyEngine().executeGeometry(FbdAssemblyAdapter.spec(compiled),
                    new MetaAssemblyEngine.Context(null,new BlockPos(p.x(),64+p.y(),p.z()),facing,null,new FbdExecutionBudget(FbdLimits.DEFAULT))));
            }
            for (boolean slope : List.of(false,true)) {
                Function<BlockPos,BlockState> terrain = p -> p.getY() <= 63-(slope ? Math.floorMod(p.getX()+p.getZ(),9)/3 : 0)
                        ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState();
                assertTrue(StructureSupportAnalysis.unsupportedBases(blocks,terrain).isEmpty(), response::toString);
                var repaired = BuildAutoRepair.applyToTerrain(terrain,-64,Blocks.COBBLESTONE.getDefaultState(),blocks,true);
                assertEquals(0,repaired.supportBlocksAdded());
                assertEquals(0,repaired.columnsFixed());
            }
        }
    }

    @Test void detachedFloorIsStillDetectedAndRepairConnectsItToRealGround() {
        Function<BlockPos,BlockState> terrain = p -> p.getY() <= 0 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState();
        var source = floor(4);
        assertEquals(16,StructureSupportAnalysis.unsupportedBases(source,terrain).size());
        var repair = BuildAutoRepair.applyToTerrain(terrain,-64,Blocks.COBBLESTONE.getDefaultState(),source,true);
        assertEquals(48,repair.supportBlocksAdded());
        assertTrue(StructureSupportAnalysis.unsupportedBases(repair.blocks(),terrain).isEmpty());
    }

    @Test void plannedAirWinsOverEarlierPlacementAndUnchangedWorldTerrain() {
        var source = floor(1);
        for (int x=0;x<4;x++) for (int z=0;z<4;z++) {
            source.add(stone(x,0,z));
            source.add(new PlannedBlock(new BlockPos(x,0,z),Blocks.AIR.getDefaultState()));
        }
        assertEquals(16,StructureSupportAnalysis.unsupportedBases(source,p -> p.getY()==0 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState()).size());
        assertEquals(16,StructureSupportAnalysis.unsupportedBases(floor(1),p -> Blocks.WATER.getDefaultState()).size());
        assertEquals(16,StructureSupportAnalysis.unsupportedBases(floor(1),p -> Blocks.SHORT_GRASS.getDefaultState()).size());
        assertTrue(StructureSupportAnalysis.carriesSupport(Blocks.OAK_STAIRS.getDefaultState()
                .with(net.minecraft.state.property.Properties.WATERLOGGED,true)));
    }

    @Test void clippingAwayTheOnlyGroundedConnectionReintroducesFloating() {
        var source = floor(4);
        for (int y=1;y<4;y++) source.add(stone(0,y,0));
        Function<BlockPos,BlockState> terrain = p -> p.getY()==0 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState();
        assertTrue(StructureSupportAnalysis.unsupportedBases(source,terrain).isEmpty());
        var clipped = source.stream().filter(b -> b.getPos().getY()==4).toList();
        assertEquals(16,StructureSupportAnalysis.unsupportedBases(clipped,terrain).size());
    }

    @Test void blockedSupportsDoNotBecomeFalseRepairSuccesses() {
        var req = new com.formacraft.common.model.request.FormaRequest();
        req.setSelectionMin(new BlockPos(0,4,0)); req.setSelectionMax(new BlockPos(3,8,3));
        Function<BlockPos,BlockState> terrain = p -> p.getY()==0 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState();
        var repair = BuildConstraintContext.withRequest(req, () -> BuildAutoRepair.applyToTerrain(terrain,-64,Blocks.COBBLESTONE.getDefaultState(),floor(4),true));
        assertEquals(0,repair.supportBlocksAdded());
        assertEquals(16,StructureSupportAnalysis.unsupportedBases(repair.blocks(),terrain).size());
    }

    @Test void finalReportClearsResolvedErrorsButPreservesRemainingErrorsAndRepairHistory() {
        var initial = new BuildQualityReport();
        initial.add(BuildQualitySeverity.ERROR,"FLOATING","before"); initial.stats().floatingColumns=16;
        initial.stats().repairedColumns=16; initial.stats().supportBlocksAdded=48;
        var delivered = new BuildQualityReport(); delivered.stats().totalBlocks=64;
        var report = BuildPreviewPipeline.finalizedReport(initial,delivered);
        assertTrue(report.recommendApply()); assertEquals(0,report.stats().floatingColumns);
        assertEquals(48,report.stats().supportBlocksAdded);
        assertTrue(report.issues().stream().anyMatch(i -> i.code().equals("INITIAL_FLOATING")));
        var failed = new BuildQualityReport(); failed.add(BuildQualitySeverity.ERROR,"FLOATING","still floating"); failed.stats().floatingColumns=12;
        assertFalse(BuildPreviewPipeline.finalizedReport(initial,failed).recommendApply());
        assertTrue(failed.summaryZh().contains("仍有 12"));
    }

    @Test void clearanceAndGeneratedSupportsShareOneUndoRedoRecord() {
        var access = new FakeBlockMutationAccess();
        for (int x=0;x<3;x++) access.states.put(new BlockPos(x,0,0),Blocks.STONE.getDefaultState());
        var tree = new BlockPos(3,1,0); access.states.put(tree,Blocks.OAK_LOG.getDefaultState());
        var original = List.of(stone(0,4,0),stone(1,4,0),stone(2,4,0),new PlannedBlock(tree,Blocks.AIR.getDefaultState()));
        var repair = BuildAutoRepair.applyToTerrain(access::getState,0,Blocks.COBBLESTONE.getDefaultState(),original,true);
        assertTrue(repair.supportBlocksAdded()>0);
        var changes = new ArrayList<BlockChange>();
        for (var block : repair.blocks()) changes.add(new BlockChange(block.getPos(),BlockSnapshot.of(access.getState(block.getPos())),BlockSnapshot.of(block.getTargetState())));
        var applied = PatchExecutor.applyToAccess(access,BlockPos.ORIGIN,FbdAssemblyAdapter.finalPatches(repair.blocks()));
        assertEquals(repair.blocks().size(),applied.applied());
        var player=UUID.randomUUID(); var world=new Object(); var undo=new UndoService();
        undo.record(player,world,BlockPos.ORIGIN,changes);
        assertTrue(undo.undo(player,world,access).complete());
        assertEquals(Blocks.OAK_LOG.getDefaultState(),access.getState(tree));
        assertTrue(access.getState(new BlockPos(0,2,0)).isAir());
        assertTrue(undo.redo(player,world,access).complete());
        assertEquals(Blocks.COBBLESTONE.getDefaultState(),access.getState(new BlockPos(0,2,0)));
        assertTrue(access.getState(tree).isAir());
    }
}
