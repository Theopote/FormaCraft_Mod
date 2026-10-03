package com.formacraft.test;

import com.formacraft.common.world.BlockMutationAccess;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import java.util.*;

public final class FakeBlockMutationAccess implements BlockMutationAccess {
    public final Map<BlockPos, BlockState> states = new HashMap<>();
    public final Set<BlockPos> rejected = new HashSet<>();
    public int writes;
    public boolean isInsideHeight(BlockPos p) { return p.getY() >= 0 && p.getY() < 10; }
    public boolean isChunkReady(BlockPos p) { return p.getX() < 16; }
    public BlockState getState(BlockPos p) {
        if (!isInsideHeight(p) || !isChunkReady(p)) throw new AssertionError("Unsafe world read " + p);
        return states.getOrDefault(p, Blocks.AIR.getDefaultState());
    }
    public boolean setState(BlockPos p, BlockState state) {
        writes++;
        if (rejected.contains(p)) return false;
        states.put(p.toImmutable(), state);
        return true;
    }
}
