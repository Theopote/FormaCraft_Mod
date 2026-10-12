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
     * 写入快照：先写 BlockState，再尽力恢复 BE NBT。
     * BE 恢复失败时记 warn，仍以 state 校验结果为准（best-effort）。
     */
    default boolean setSnapshot(BlockPos pos, BlockSnapshot snapshot) {
        if (snapshot == null) return false;
        boolean accepted = setState(pos, snapshot.state());
        if (!accepted) return false;
        return getState(pos).equals(snapshot.state());
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
                    LOGGER.warn("Failed to serialize block entity at {}; keeping state-only snapshot", pos, e);
                    return BlockSnapshot.of(state);
                }
            }

            @Override
            public boolean setSnapshot(BlockPos pos, BlockSnapshot snapshot) {
                if (snapshot == null) return false;
                boolean accepted = world.setBlockState(pos, snapshot.state(), 3);
                if (!accepted) return false;

                NbtCompound nbt = snapshot.blockEntityNbt();
                if (nbt != null && !nbt.isEmpty()) {
                    try {
                        BlockEntity existing = world.getBlockEntity(pos);
                        if (existing != null) {
                            try (ErrorReporter.Logging logging =
                                         new ErrorReporter.Logging(existing.getReporterContext(), LOGGER)) {
                                existing.read(NbtReadView.create(logging, world.getRegistryManager(), nbt));
                            }
                        } else {
                            BlockEntity created = BlockEntity.createFromNbt(
                                    pos, snapshot.state(), nbt, world.getRegistryManager());
                            if (created != null) {
                                world.addBlockEntity(created);
                            } else {
                                LOGGER.warn(
                                        "Could not recreate block entity at {} from NBT; state restored only",
                                        pos);
                            }
                        }
                    } catch (RuntimeException e) {
                        LOGGER.warn(
                                "Failed to restore block entity NBT at {}; state restored only",
                                pos, e);
                    }
                }

                return world.getBlockState(pos).equals(snapshot.state());
            }
        };
    }
}
