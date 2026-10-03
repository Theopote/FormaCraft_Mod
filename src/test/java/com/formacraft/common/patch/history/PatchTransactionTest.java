package com.formacraft.common.patch.history;

import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PatchTransactionTest {
    @BeforeAll static void initialize() { MinecraftRegistryTestBootstrap.initialize(); }
    @Test void noFinalDifferenceProducesNoHistoryOrMemoryPatches() {
        var state = Map.of(BlockPos.ORIGIN, Blocks.STONE.getDefaultState());
        var tx = PatchTransaction.fromSnapshots(BlockPos.ORIGIN, state, state);
        assertTrue(tx.before().isEmpty());
        assertTrue(tx.after().isEmpty());
        assertTrue(tx.patches().isEmpty());
    }
    @Test void reverseMemoryPatchRestoresOriginalMaterialInsteadOfRemovingReplacement() {
        BlockPos origin = new BlockPos(10, 1, -3);
        BlockPos pos = origin.add(-2, 3, 4);
        var tx = PatchTransaction.fromSnapshots(origin, Map.of(pos, Blocks.STONE.getDefaultState()),
                Map.of(pos, Blocks.OAK_PLANKS.getDefaultState()));
        assertEquals("minecraft:oak_planks", tx.patches().getFirst().targetBlock());
        var reverse = PatchTransaction.patchesForStates(origin, tx.before()).getFirst();
        assertEquals("replace", reverse.action());
        assertEquals("minecraft:stone", reverse.targetBlock());
        assertEquals(-2, reverse.dx());
        assertEquals(3, reverse.dy());
        assertEquals(4, reverse.dz());
    }
}
