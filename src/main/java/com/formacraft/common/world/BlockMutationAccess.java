package com.formacraft.common.world;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Minimal world operations used by execution; tests can model rejection without starting a server. */
public interface BlockMutationAccess {
    Logger LOGGER = LoggerFactory.getLogger("FormaCraft/BlockMutationAccess");

    boolean isInsideHeight(BlockPos pos);
    boolean isChunkReady(BlockPos pos);
    BlockState getState(BlockPos pos);
    boolean setState(BlockPos pos, BlockState state);

    default BlockSnapshot getSnapshot(BlockPos pos) {
        BlockState state = getState(pos);
        return BlockSnapshot.of(state);
    }

    /**
     * 写入快照并返回分级结果。默认实现仅保证状态；完整 NBT 由生产适配器处理。
     */
    default SnapshotWriteResult writeSnapshot(BlockPos pos, BlockSnapshot snapshot) {
        if (snapshot == null) return SnapshotWriteResult.REJECTED;
        try {
            boolean accepted = setState(pos, snapshot.state());
            if (!accepted) return SnapshotWriteResult.REJECTED;
            BlockSnapshot after = getSnapshot(pos);
            if (after.equals(snapshot)) return SnapshotWriteResult.FULL;
            if (after.state().equals(snapshot.state())) {
                return snapshot.blockEntityNbt() != null
                        ? SnapshotWriteResult.STATE_ONLY
                        : SnapshotWriteResult.FULL;
            }
            return SnapshotWriteResult.STATE_ONLY;
        } catch (RuntimeException e) {
            LOGGER.warn("writeSnapshot failed at {}", pos, e);
            return SnapshotWriteResult.ERROR;
        }
    }

    /** 兼容：仅 FULL 视为成功。 */
    default boolean setSnapshot(BlockPos pos, BlockSnapshot snapshot) {
        return writeSnapshot(pos, snapshot).isFullSuccess();
    }

}
