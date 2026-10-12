package com.formacraft.common.world;

import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 方块快照：BlockState + 可选方块实体 NBT（含 id，便于 undo 恢复）。
 * <p>
 * 无法序列化的 BE / 跨版本非法 NBT 时允许 nbt 为 null，仅恢复 BlockState（best-effort）。
 */
public record BlockSnapshot(BlockState state, @Nullable NbtCompound blockEntityNbt) {
    public BlockSnapshot {
        Objects.requireNonNull(state, "state");
        if (blockEntityNbt != null) {
            blockEntityNbt = blockEntityNbt.copy();
        }
    }

    public static BlockSnapshot of(BlockState state) {
        return new BlockSnapshot(state, null);
    }

    public static BlockSnapshot of(BlockState state, @Nullable NbtCompound nbt) {
        return new BlockSnapshot(state, nbt);
    }

    /** 状态与 NBT 均相同才视为同一快照（NBT 按内容比较）。 */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockSnapshot other)) return false;
        if (!state.equals(other.state)) return false;
        if (blockEntityNbt == null) return other.blockEntityNbt == null;
        return other.blockEntityNbt != null && blockEntityNbt.equals(other.blockEntityNbt);
    }

    @Override
    public int hashCode() {
        return 31 * state.hashCode() + (blockEntityNbt == null ? 0 : blockEntityNbt.hashCode());
    }
}
