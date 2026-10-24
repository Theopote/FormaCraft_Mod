package com.formacraft.common.patch.history;

import com.formacraft.common.world.BlockSnapshot;
import net.minecraft.block.PaneBlock;
import net.minecraft.state.property.Properties;
import java.util.List;

/** Replay-only equivalence for pane connections derived from neighboring blocks. */
final class ReplaySnapshotMatcher {
    private ReplaySnapshotMatcher() {}

    static boolean matches(BlockSnapshot actual, BlockSnapshot expected) {
        if (actual.equals(expected)) return true;
        if (!(actual.state().getBlock() instanceof PaneBlock)
                || actual.state().getBlock() != expected.state().getBlock()) return false;
        var normalized = actual.state();
        for (var property : List.of(Properties.NORTH, Properties.EAST, Properties.SOUTH, Properties.WEST)) {
            if (normalized.contains(property) && expected.state().contains(property))
                normalized = normalized.with(property, expected.state().get(property));
        }
        // Everything else (including waterlogging and NBT) is still exact.
        return BlockSnapshot.of(normalized, actual.blockEntityNbt()).equals(expected);
    }
}
