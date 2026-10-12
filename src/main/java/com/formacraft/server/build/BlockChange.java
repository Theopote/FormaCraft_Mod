package com.formacraft.server.build;

import com.formacraft.common.world.BlockSnapshot;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

/**
 * 方块变更记录：from → to（含可选 BE NBT），供 Undo 使用。
 */
public class BlockChange {
    private final BlockPos pos;
    private final BlockSnapshot from;
    private final BlockSnapshot to;

    public BlockChange(BlockPos pos, BlockSnapshot from, BlockSnapshot to) {
        this.pos = pos;
        this.from = from;
        this.to = to;
    }

    /** 兼容仅 BlockState 的构造。 */
    public BlockChange(BlockPos pos, BlockState fromState, BlockState toState) {
        this(pos, BlockSnapshot.of(fromState), BlockSnapshot.of(toState));
    }

    public BlockPos getPos() {
        return pos;
    }

    public BlockSnapshot getFrom() {
        return from;
    }

    public BlockSnapshot getTo() {
        return to;
    }

    public BlockState getFromState() {
        return from.state();
    }

    public BlockState getToState() {
        return to.state();
    }
}
