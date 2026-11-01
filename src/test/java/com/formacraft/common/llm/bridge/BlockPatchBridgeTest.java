package com.formacraft.common.llm.bridge;

import com.formacraft.common.llm.dto.PatchBlock;
import com.formacraft.common.llm.dto.PatchBlockSection;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.ConfirmedPlanIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlockPatchBridgeTest {

    @Test
    void toBlockPatchesCollapsesSameCellToLastWrite() {
        PatchBlockSection section = new PatchBlockSection(
                new Vec3i(0, 64, 0),
                List.of(
                        new PatchBlock("place", 1, 0, 0, "minecraft:stone"),
                        new PatchBlock("place", 1, 0, 0, "minecraft:glass"),
                        new PatchBlock("place", 2, 0, 0, "minecraft:oak_planks")
                )
        );
        List<BlockPatch> patches = BlockPatchBridge.toBlockPatches(section);
        assertEquals(2, patches.size());
        BlockPatch atCell = patches.stream()
                .filter(p -> p.dx() == 1 && p.dy() == 0 && p.dz() == 0)
                .findFirst()
                .orElseThrow();
        assertEquals("minecraft:glass", atCell.targetBlock());
        assertEquals(ConfirmedPlanIdentity.toFinalPatches(patches), patches);
    }

    @Test
    void removeNormalizesTargetToAir() {
        PatchBlockSection section = new PatchBlockSection(
                null,
                List.of(new PatchBlock("remove", 0, 1, 0, "minecraft:whatever"))
        );
        List<BlockPatch> patches = BlockPatchBridge.toBlockPatches(section);
        assertEquals(1, patches.size());
        assertEquals(BlockPatch.REMOVE, patches.getFirst().action());
        assertEquals("minecraft:air", patches.getFirst().targetBlock());
    }
}
