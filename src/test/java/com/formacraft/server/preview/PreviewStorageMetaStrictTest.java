package com.formacraft.server.preview;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PreviewMeta 必须在 store 时建立；digest 不一致即失效。
 * 完整 validatePreview 依赖 ServerPlayerEntity，此处覆盖 digest 契约。
 */
class PreviewStorageMetaStrictTest {

    @Test
    void digestChangesWhenStructureDiffers() {
        GeneratedStructure a = new GeneratedStructure(
                UUID.randomUUID(), new BlockPos(0, 64, 0), "a",
                List.of(new PlannedBlock(new BlockPos(1, 2, 3), null)));
        GeneratedStructure b = new GeneratedStructure(
                UUID.randomUUID(), new BlockPos(0, 64, 0), "b",
                List.of(new PlannedBlock(new BlockPos(9, 9, 9), null)));
        assertNotEquals(PreviewStorage.digestStructure(a), PreviewStorage.digestStructure(b));
    }

    @Test
    void digestStableForIdenticalLayout() {
        List<PlannedBlock> blocks = List.of(new PlannedBlock(new BlockPos(1, 2, 3), null));
        GeneratedStructure a = new GeneratedStructure(UUID.randomUUID(), new BlockPos(0, 64, 0), "a", blocks);
        GeneratedStructure b = new GeneratedStructure(UUID.randomUUID(), new BlockPos(0, 64, 0), "b", blocks);
        assertEquals(PreviewStorage.digestStructure(a), PreviewStorage.digestStructure(b));
    }
}
