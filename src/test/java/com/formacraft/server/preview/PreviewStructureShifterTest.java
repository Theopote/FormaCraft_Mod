package com.formacraft.server.preview;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.server.build.quality.BuildQualityReport;
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
        var shifted = PreviewStructureShifter.shift(structure, 3, -2, 4);
        assertNotNull(shifted);
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
        BuildQualityReport report = PreviewStructureShifter.recheckAtWorld(structure, null);
        assertNotNull(report);
        assertFalse(report.hasFatal());
        assertTrue(report.stats().totalBlocks >= 1);
    }
}
