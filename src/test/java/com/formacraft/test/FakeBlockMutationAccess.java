package com.formacraft.test;

import com.formacraft.common.world.BlockMutationAccess;
import com.formacraft.common.world.BlockSnapshot;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import java.util.*;
import java.util.function.Predicate;

public final class FakeBlockMutationAccess implements BlockMutationAccess {
    public final Map<BlockPos, BlockState> states = new HashMap<>();
    public final Map<BlockPos, NbtCompound> blockEntityNbt = new HashMap<>();
    public final Set<BlockPos> rejected = new HashSet<>();
    public final Set<BlockPos> throwOnWrite = new HashSet<>();
    /** null = default x&lt;16 规则；非 null 时覆盖。 */
    public Predicate<BlockPos> chunkReadyOverride = null;
    public int writes;

    public boolean isInsideHeight(BlockPos p) { return p.getY() >= 0 && p.getY() < 10; }

    public boolean isChunkReady(BlockPos p) {
        if (chunkReadyOverride != null) return chunkReadyOverride.test(p);
        return p.getX() < 16;
    }

    public void setChunkReady(BlockPos p, boolean ready) {
        Map<BlockPos, Boolean> overrides = new HashMap<>();
        if (chunkReadyOverride instanceof ChunkReadyMap crm) {
            overrides.putAll(crm.map);
        }
        overrides.put(p.toImmutable(), ready);
        chunkReadyOverride = new ChunkReadyMap(overrides);
    }

    public BlockState getState(BlockPos p) {
        if (!isInsideHeight(p) || !isChunkReady(p)) throw new AssertionError("Unsafe world read " + p);
        return states.getOrDefault(p, Blocks.AIR.getDefaultState());
    }

    public boolean setState(BlockPos p, BlockState state) {
        writes++;
        if (throwOnWrite.contains(p)) {
            throw new RuntimeException("simulated write failure at " + p);
        }
        if (rejected.contains(p)) return false;
        states.put(p.toImmutable(), state);
        if (state == null || state.isAir()) {
            blockEntityNbt.remove(p.toImmutable());
        }
        return true;
    }

    @Override
    public BlockSnapshot getSnapshot(BlockPos p) {
        BlockState state = getState(p);
        NbtCompound nbt = blockEntityNbt.get(p.toImmutable());
        return BlockSnapshot.of(state, nbt == null ? null : nbt.copy());
    }

    @Override
    public boolean setSnapshot(BlockPos p, BlockSnapshot snapshot) {
        if (snapshot == null) return false;
        boolean ok = setState(p, snapshot.state());
        if (!ok) return false;
        if (snapshot.blockEntityNbt() != null) {
            blockEntityNbt.put(p.toImmutable(), snapshot.blockEntityNbt().copy());
        } else {
            blockEntityNbt.remove(p.toImmutable());
        }
        return getState(p).equals(snapshot.state());
    }

    private static final class ChunkReadyMap implements Predicate<BlockPos> {
        final Map<BlockPos, Boolean> map;
        final Predicate<BlockPos> fallback = p -> p.getX() < 16;
        ChunkReadyMap(Map<BlockPos, Boolean> map) { this.map = map; }
        @Override public boolean test(BlockPos p) {
            Boolean v = map.get(p.toImmutable());
            return v != null ? v : fallback.test(p);
        }
    }
}
