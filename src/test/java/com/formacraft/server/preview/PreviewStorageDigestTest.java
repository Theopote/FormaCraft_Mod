package com.formacraft.server.preview;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Digest 单元测试不启动 Minecraft 方块注册表；用 null BlockState + 坐标区分。
 */
class PreviewStorageDigestTest {

    @Test
    void digestStableForSameBlocks() {
        GeneratedStructure a = structure(List.of(
                new PlannedBlock(new BlockPos(1, 2, 3), null),
                new PlannedBlock(new BlockPos(1, 3, 3), null)
        ));
        GeneratedStructure b = structure(List.of(
                new PlannedBlock(new BlockPos(1, 2, 3), null),
                new PlannedBlock(new BlockPos(1, 3, 3), null)
        ));
        assertEquals(PreviewStorage.digestStructure(a), PreviewStorage.digestStructure(b));
    }

    @Test
    void digestChangesWhenBlockDiffers() {
        GeneratedStructure a = structure(List.of(
                new PlannedBlock(new BlockPos(1, 2, 3), null)
        ));
        GeneratedStructure b = structure(List.of(
                new PlannedBlock(new BlockPos(1, 2, 4), null)
        ));
        assertNotEquals(PreviewStorage.digestStructure(a), PreviewStorage.digestStructure(b));
    }

    private static GeneratedStructure structure(List<PlannedBlock> blocks) {
        return new GeneratedStructure(UUID.randomUUID(), new BlockPos(0, 64, 0), "test", blocks);
    }
}
