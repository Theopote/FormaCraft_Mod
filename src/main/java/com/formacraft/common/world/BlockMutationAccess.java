package com.formacraft.common.world;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.NbtReadView;
import net.minecraft.util.ErrorReporter;
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

    static BlockMutationAccess forWorld(ServerWorld world) {
        java.util.Objects.requireNonNull(world, "world");
        return new BlockMutationAccess() {
            @Override
            public boolean isInsideHeight(BlockPos pos) {
                return WorldBuildBounds.isInsideWorldHeight(world, pos);
            }

            @Override
            public boolean isChunkReady(BlockPos pos) {
                return WorldBuildBounds.isChunkReady(world, pos);
            }

            @Override
            public BlockState getState(BlockPos pos) {
                return world.getBlockState(pos);
            }

            @Override
            public boolean setState(BlockPos pos, BlockState state) {
                return world.setBlockState(pos, state, 3);
            }

            @Override
            public BlockSnapshot getSnapshot(BlockPos pos) {
                BlockState state = world.getBlockState(pos);
                BlockEntity be = world.getBlockEntity(pos);
                if (be == null) {
                    return BlockSnapshot.of(state);
                }
                try {
                    NbtCompound nbt = be.createNbtWithIdentifyingData(world.getRegistryManager());
                    return BlockSnapshot.of(state, nbt);
                } catch (RuntimeException e) {
                    LOGGER.warn("Failed to serialize block entity at {}; state-only snapshot (not FULL-guaranteed)",
                            pos, e);
                    return BlockSnapshot.of(state);
                }
            }

            @Override
            public SnapshotWriteResult writeSnapshot(BlockPos pos, BlockSnapshot snapshot) {
                if (snapshot == null) return SnapshotWriteResult.REJECTED;
                try {
                    boolean accepted = world.setBlockState(pos, snapshot.state(), 3);
                    if (!accepted) return SnapshotWriteResult.REJECTED;

                    NbtCompound nbt = snapshot.blockEntityNbt();
                    boolean nbtOk = true;
                    if (nbt != null && !nbt.isEmpty()) {
                        nbtOk = false;
                        try {
                            BlockEntity existing = world.getBlockEntity(pos);
                            if (existing != null) {
                                try (ErrorReporter.Logging logging =
                                             new ErrorReporter.Logging(existing.getReporterContext(), LOGGER)) {
                                    existing.read(NbtReadView.create(logging, world.getRegistryManager(), nbt));
                                }
                                nbtOk = true;
                            } else {
                                BlockEntity created = BlockEntity.createFromNbt(
                                        pos, snapshot.state(), nbt, world.getRegistryManager());
                                if (created != null) {
                                    world.addBlockEntity(created);
                                    nbtOk = true;
                                } else {
                                    LOGGER.warn(
                                            "Could not recreate block entity at {} from NBT; STATE_ONLY",
                                            pos);
                                }
                            }
                        } catch (RuntimeException e) {
                            LOGGER.warn("Failed to restore block entity NBT at {}; STATE_ONLY", pos, e);
                        }
                    }

                    BlockSnapshot after = getSnapshot(pos);
                    if (after.equals(snapshot)) return SnapshotWriteResult.FULL;
                    if (after.state().equals(snapshot.state())) {
                        return (nbt != null && !nbt.isEmpty() && !nbtOk)
                                ? SnapshotWriteResult.STATE_ONLY
                                : (nbt != null && !nbt.isEmpty() ? SnapshotWriteResult.STATE_ONLY : SnapshotWriteResult.FULL);
                    }
                    return SnapshotWriteResult.STATE_ONLY;
                } catch (RuntimeException e) {
                    LOGGER.warn("writeSnapshot failed at {}", pos, e);
                    return SnapshotWriteResult.ERROR;
                }
            }
        };
    }
}
