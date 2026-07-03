package com.formacraft.server.build;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

/**
 * 方块变更记录
 * 真正执行方块修改时，我们需要记录 from → to，以便 Undo 使用
 */
public class BlockChange {
    private final BlockPos pos;
    private final BlockState fromState;
    private final BlockState toState;

    public BlockChange(BlockPos pos, BlockState fromState, BlockState toState) {
        this.pos = pos;
        this.fromState = fromState;
        this.toState = toState;
    }

    public BlockPos getPos() {
        return pos;
    }

    public BlockState getFromState() {
        return fromState;
    }

    public BlockState getToState() {
        return toState;
    }
}

