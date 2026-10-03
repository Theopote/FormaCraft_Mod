package com.formacraft.common.world;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** Minimal world operations used by execution; tests can model rejection without starting a server. */
public interface BlockMutationAccess {
    boolean isInsideHeight(BlockPos pos);
    boolean isChunkReady(BlockPos pos);
    BlockState getState(BlockPos pos);
    boolean setState(BlockPos pos, BlockState state);

    static BlockMutationAccess forWorld(ServerWorld world) {
        java.util.Objects.requireNonNull(world, "world");
        return new BlockMutationAccess() {
            public boolean isInsideHeight(BlockPos pos) { return WorldBuildBounds.isInsideWorldHeight(world, pos); }
            public boolean isChunkReady(BlockPos pos) { return WorldBuildBounds.isChunkReady(world, pos); }
            public BlockState getState(BlockPos pos) { return world.getBlockState(pos); }
            public boolean setState(BlockPos pos, BlockState state) { return world.setBlockState(pos, state, 3); }
        };
    }
}
