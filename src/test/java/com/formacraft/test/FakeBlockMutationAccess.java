package com.formacraft.test;

import com.formacraft.common.world.BlockMutationAccess;
import com.formacraft.common.world.BlockSnapshot;
import com.formacraft.common.world.SnapshotWriteResult;
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
    /** 写入状态成功但拒绝写入/保留 NBT（模拟 STATE_ONLY）。 */
    public final Set<BlockPos> failNbtRestore = new HashSet<>();
    /** 写入会改世界但 setState 返回 false（模拟漏记边界）。 */
    public final Set<BlockPos> mutateThenReject = new HashSet<>();
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
        if (mutateThenReject.contains(p)) {
            states.put(p.toImmutable(), state);
            return false;
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
    public SnapshotWriteResult writeSnapshot(BlockPos p, BlockSnapshot snapshot) {
        if (snapshot == null) return SnapshotWriteResult.REJECTED;
        try {
            boolean ok = setState(p, snapshot.state());
            if (!ok) {
                // 可能已改世界（mutateThenReject）
                return SnapshotWriteResult.REJECTED;
            }
            if (snapshot.blockEntityNbt() != null) {
                if (failNbtRestore.contains(p)) {
                    blockEntityNbt.remove(p.toImmutable());
                    return SnapshotWriteResult.STATE_ONLY;
                }
                blockEntityNbt.put(p.toImmutable(), snapshot.blockEntityNbt().copy());
            } else {
                blockEntityNbt.remove(p.toImmutable());
            }
            BlockSnapshot after = getSnapshot(p);
            return after.equals(snapshot) ? SnapshotWriteResult.FULL : SnapshotWriteResult.STATE_ONLY;
        } catch (RuntimeException e) {
            return SnapshotWriteResult.ERROR;
        }
    }

    @Override
    public boolean setSnapshot(BlockPos p, BlockSnapshot snapshot) {
        return writeSnapshot(p, snapshot).isFullSuccess();
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
