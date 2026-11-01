package com.formacraft.server.phase2;

import com.formacraft.common.world.BlockMutationAccess;
import com.formacraft.common.world.BlockSnapshot;
import com.formacraft.common.world.SnapshotWriteResult;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

/** Fake world for Phase 2 Apply：宽松高度/区块，避免 FakeBlockMutationAccess 的测试夹具限制。 */
public final class Phase2UnboundedAccess implements BlockMutationAccess {
    public final Map<BlockPos, BlockState> states = new HashMap<>();
    public int writes;

    @Override
    public boolean isInsideHeight(BlockPos pos) {
        return pos != null && pos.getY() >= -64 && pos.getY() <= 320;
    }

    @Override
    public boolean isChunkReady(BlockPos pos) {
        return pos != null;
    }

    @Override
    public BlockState getState(BlockPos pos) {
        return states.getOrDefault(pos.toImmutable(), Blocks.AIR.getDefaultState());
    }

    @Override
    public boolean setState(BlockPos pos, BlockState state) {
        writes++;
        BlockPos key = pos.toImmutable();
        if (state == null || state.isAir()) {
            states.remove(key);
        } else {
            states.put(key, state);
        }
        return true;
    }

    @Override
    public BlockSnapshot getSnapshot(BlockPos pos) {
        return BlockSnapshot.of(getState(pos));
    }

    @Override
    public SnapshotWriteResult writeSnapshot(BlockPos pos, BlockSnapshot snapshot) {
        if (snapshot == null) return SnapshotWriteResult.REJECTED;
        setState(pos, snapshot.state());
        return SnapshotWriteResult.FULL;
    }
}
