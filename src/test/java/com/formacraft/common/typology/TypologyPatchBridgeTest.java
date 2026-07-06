package com.formacraft.common.typology;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TypologyPatchBridgeTest {

    @AfterEach
    void clearAnchor() {
        TypologyPatchBridge.clearPlanWorldAnchor();
    }

    @Test
    void worldBuildOriginCombinesPlanAnchorAndSlotOffset() {
        TypologyPatchBridge.setPlanWorldAnchor(new BlockPos(177, 67, -5));
        SemanticComponent semantic = new SemanticComponent(
                "STRUCTURE",
                new Slot("__global__", new Vec3i(2, 1, -3), null, "default", null, null),
                new Component("STRUCTURE", null, new Vec3i(0, 0, 0), null, List.of("typology:suspension_bridge"), null)
        );

        BlockPos worldOrigin = TypologyPatchBridge.worldBuildOrigin(semantic);

        assertEquals(new BlockPos(179, 68, -8), worldOrigin);
    }

    @Test
    void toBlockPatchesSubtractsWorldBuildOrigin() {
        BlockPos worldOrigin = new BlockPos(177, 67, -5);
        GeneratedStructure structure = new GeneratedStructure(
                null,
                worldOrigin,
                "test",
                List.of(new PlannedBlock(new BlockPos(200, 68, -5), Blocks.STONE.getDefaultState()))
        );

        List<BlockPatch> patches = TypologyPatchBridge.toBlockPatches(structure, worldOrigin);

        assertEquals(1, patches.size());
        assertEquals(23, patches.get(0).dx());
        assertEquals(1, patches.get(0).dy());
        assertEquals(0, patches.get(0).dz());
    }
}
