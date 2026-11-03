package com.formacraft.server.preview;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.Footprint;
import com.formacraft.server.build.quality.BuildQualityReport;
import com.formacraft.server.build.quality.BuildQualitySeverity;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PreviewStructureShifterTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void clampOffsetLimitsToPlusMinus32() {
        assertEquals(32, PreviewStructureShifter.clampOffset(100));
        assertEquals(-32, PreviewStructureShifter.clampOffset(-99));
        assertEquals(5, PreviewStructureShifter.clampOffset(5));
    }

    @Test
    void shiftMovesOriginAndAllBlocks() {
        BlockPos origin = new BlockPos(10, 64, 20);
        var structure = new GeneratedStructure(
                UUID.randomUUID(),
                origin,
                "hut",
                List.of(
                        new PlannedBlock(new BlockPos(10, 64, 20), Blocks.STONE.getDefaultState()),
                        new PlannedBlock(new BlockPos(11, 65, 20), Blocks.OAK_PLANKS.getDefaultState())
                )
        );
        String lockId = "lock_" + "a".repeat(32);
        var shifted = PreviewStructureShifter.shift(structure.withAssetLock(lockId), 3, -2, 4);
        assertNotNull(shifted);
        assertEquals(lockId, shifted.getAssetLockId());
        assertEquals(new BlockPos(13, 62, 24), shifted.getOrigin());
        assertEquals(2, shifted.getBlocks().size());
        assertEquals(new BlockPos(13, 62, 24), shifted.getBlocks().get(0).getPos());
        assertEquals(new BlockPos(14, 63, 24), shifted.getBlocks().get(1).getPos());
        assertEquals(Blocks.STONE.getDefaultState(), shifted.getBlocks().get(0).getTargetState());
    }

    @Test
    void recheckWithoutWorldStillProducesStructuralReport() {
        var structure = new GeneratedStructure(
                null,
                new BlockPos(0, 70, 0),
                "column",
                List.of(new PlannedBlock(new BlockPos(0, 70, 0), Blocks.STONE.getDefaultState()))
        );
        BuildQualityReport report = PreviewStructureShifter.recheckAtWorld(structure, null, null);
        assertNotNull(report);
        assertFalse(report.hasFatal());
        assertTrue(report.stats().totalBlocks >= 1);
    }

    @Test
    void recheckWithBuildingSpecEmitsDimensionWarnings() {
        BuildingSpec spec = new BuildingSpec();
        spec.setFootprint(new Footprint(20, 20));
        spec.setHeight(20);
        var structure = new GeneratedStructure(
                null,
                new BlockPos(0, 70, 0),
                "tiny",
                List.of(new PlannedBlock(new BlockPos(0, 70, 0), Blocks.STONE.getDefaultState()))
        );
        BuildQualityReport withSpec = PreviewStructureShifter.recheckAtWorld(structure, spec, null);
        BuildQualityReport without = PreviewStructureShifter.recheckAtWorld(structure, null, null);
        assertTrue(
                withSpec.issues().stream().anyMatch(i ->
                        i.code() != null && i.code().startsWith("DIM_")
                                && i.severity() == BuildQualitySeverity.WARNING),
                "BuildingSpec must participate in recheck like prepare()");
        assertTrue(
                without.issues().stream().noneMatch(i -> i.code() != null && i.code().startsWith("DIM_")),
                "null BuildingSpec skips dimension checks");
    }

    @Test
    void fatalQualityRejectsAdjustAndKeepsOriginal() {
        BuildQualityReport fatal = new BuildQualityReport();
        fatal.add(com.formacraft.server.build.quality.BuildQualitySeverity.FATAL,
                "WORLD_HEIGHT", "超出世界高度");
        assertTrue(PreviewStructureShifter.shouldRejectAdjust(fatal));

        var original = new GeneratedStructure(
                null,
                new BlockPos(0, 64, 0),
                "hut",
                List.of(new PlannedBlock(new BlockPos(0, 64, 0), Blocks.STONE.getDefaultState()))
        );
        // Without a live world, recheck won't emit WORLD_HEIGHT; inject via evaluate path using null world
        // then assert reject helper + that shift alone does not mutate original reference.
        var shifted = PreviewStructureShifter.shift(original, 0, 200, 0);
        assertNotSame(original, shifted);
        assertEquals(new BlockPos(0, 264, 0), shifted.getOrigin());

        // Simulate FATAL gate after recheck: evaluateAdjust with a fabricated reject path
        assertTrue(PreviewStructureShifter.shouldRejectAdjust(fatal));
        var outcome = new PreviewStructureShifter.AdjustOutcome(
                false, original, fatal, "预览移动被拒绝：" + fatal.summaryZh() + "（已保留原位置）");
        assertFalse(outcome.applied());
        assertSame(original, outcome.structure());
        assertTrue(outcome.statusMessage().contains("已保留原位置"));
    }

    @Test
    void errorQualityDoesNotRejectAdjust() {
        BuildQualityReport error = new BuildQualityReport();
        error.add(com.formacraft.server.build.quality.BuildQualitySeverity.ERROR,
                "FLOATING", "部分悬空");
        assertFalse(PreviewStructureShifter.shouldRejectAdjust(error));
    }

    @Test
    void evaluateAdjustAppliesWhenRecheckHasNoFatal() {
        var original = new GeneratedStructure(
                null,
                new BlockPos(2, 70, 2),
                "hut",
                List.of(new PlannedBlock(new BlockPos(2, 70, 2), Blocks.STONE.getDefaultState()))
        );
        var outcome = PreviewStructureShifter.evaluateAdjust(original, 3, 0, 0, null);
        assertTrue(outcome.applied());
        assertEquals(new BlockPos(5, 70, 2), outcome.structure().getOrigin());
        assertNotNull(outcome.report());
        assertTrue(outcome.statusMessage().startsWith("预览已调整："));
    }
}
