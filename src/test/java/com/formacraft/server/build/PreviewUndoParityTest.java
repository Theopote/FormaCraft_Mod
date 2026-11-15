package com.formacraft.server.build;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.ConfirmedPlanIdentity;
import com.formacraft.server.build.quality.BuildQualityGate;
import com.formacraft.server.build.quality.BuildQualityReport;
import com.formacraft.server.build.quality.BuildQualitySeverity;
import com.formacraft.server.preview.PreviewStorage;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR-4: freeze Build vs Patch confirm/undo parity without merging storages.
 * See docs/PREVIEW_UNDO_INVARIANTS.md.
 */
class PreviewUndoParityTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void buildAndPatchShareQualityGateSemantics() {
        BuildQualityReport report = new BuildQualityReport();
        report.add(BuildQualitySeverity.ERROR, "E_TEST", "needs force");
        var snap = BuildQualityGate.QualitySnapshot.from(report);

        assertEquals(BuildQualityGate.Decision.REQUIRE_FORCE, BuildQualityGate.decide(report, false));
        assertEquals(BuildQualityGate.Decision.REQUIRE_FORCE, BuildQualityGate.decide(snap, false));
        assertEquals(BuildQualityGate.Decision.ALLOW, BuildQualityGate.decide(report, true));
        assertEquals(BuildQualityGate.Decision.ALLOW, BuildQualityGate.decide(snap, true));

        report.add(BuildQualitySeverity.FATAL, "F_TEST", "fatal");
        snap = BuildQualityGate.QualitySnapshot.from(report);
        assertEquals(BuildQualityGate.Decision.DENY_FATAL, BuildQualityGate.decide(report, true));
        assertEquals(BuildQualityGate.Decision.DENY_FATAL, BuildQualityGate.decide(snap, true));
    }

    @Test
    void errorWithForceAcceptedOnBuildCommitGate() {
        AtomicBoolean consumed = new AtomicBoolean(false);
        BuildQualityReport report = new BuildQualityReport();
        report.add(BuildQualitySeverity.ERROR, "E", "soft");
        var status = BuildConfirmService.commitIfEnqueued(
                true, true, true, true,
                report, true,
                true,
                () -> consumed.set(true)
        );
        assertEquals(BuildConfirmService.Status.ACCEPTED, status);
        assertTrue(consumed.get());
    }

    @Test
    void confirmedPlanIdentityLastWriteWinsMatchesPreviewDigestContract() {
        // Patch tickets freeze via toFinalPatches; Build digests final cell state — same last-write rule.
        List<BlockPatch> patches = new ArrayList<>();
        patches.add(new BlockPatch(BlockPatch.PLACE, 1, 2, 3, "minecraft:dirt"));
        patches.add(new BlockPatch(BlockPatch.PLACE, 1, 2, 3, "minecraft:stone"));
        List<BlockPatch> finals = ConfirmedPlanIdentity.toFinalPatches(patches);
        assertEquals(1, finals.size());
        assertEquals("minecraft:stone", finals.getFirst().targetBlock());

        GeneratedStructure withOverwrite = new GeneratedStructure(
                UUID.randomUUID(), new BlockPos(0, 64, 0), "t",
                List.of(
                        new PlannedBlock(new BlockPos(1, 2, 3), Blocks.DIRT.getDefaultState()),
                        new PlannedBlock(new BlockPos(1, 2, 3), Blocks.STONE.getDefaultState())
                ));
        GeneratedStructure finalOnly = new GeneratedStructure(
                UUID.randomUUID(), new BlockPos(0, 64, 0), "t",
                List.of(new PlannedBlock(new BlockPos(1, 2, 3), Blocks.STONE.getDefaultState()))
        );
        assertEquals(PreviewStorage.digestStructure(withOverwrite), PreviewStorage.digestStructure(finalOnly));
    }

    @Test
    void generationBumpInvalidatesOldConfirmSnapshot() {
        var structure = new GeneratedStructure(
                UUID.randomUUID(), new BlockPos(2, 70, 2), "hut",
                List.of(new PlannedBlock(new BlockPos(2, 70, 2), Blocks.STONE.getDefaultState()))
        );
        String digest = PreviewStorage.digestStructure(structure);
        var snapshotGen5 = new PreviewStorage.PreviewSnapshot(
                structure, digest, new BlockPos(2, 70, 2), 5L, null, null);
        var metaGen5 = new PreviewStorage.PreviewMeta(
                null, new BlockPos(2, 70, 2), digest, System.currentTimeMillis(), 5L);
        var metaGen6 = new PreviewStorage.PreviewMeta(
                null, new BlockPos(2, 70, 2), digest, System.currentTimeMillis(), 6L);

        assertTrue(snapshotGen5.matchesMeta(metaGen5));
        assertFalse(snapshotGen5.matchesMeta(metaGen6),
                "duplicate confirm against a newer preview generation must fail identity match");
    }

    @Test
    void dimensionMismatchRefusesBeforeConsume() {
        AtomicBoolean consumed = new AtomicBoolean(false);
        assertEquals(BuildConfirmService.Status.WORLD_MISMATCH,
                BuildConfirmService.commitIfEnqueued(
                        true, true, false, true, null, false, true, () -> consumed.set(true)));
        assertFalse(consumed.get());
    }
}
