package com.formacraft.common.build;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

/**
 * 计划放置的方块
 * 生成器只需要关心"要在某个坐标放什么方块"，不用管原始方块是什么
 */
public class PlannedBlock {
    private final BlockPos pos;
    private final BlockState targetState;

    public PlannedBlock(BlockPos pos, BlockState targetState) {
        this.pos = pos;
        this.targetState = targetState;
    }

    public BlockPos getPos() {
        return pos;
    }

    public BlockState getTargetState() {
        return targetState;
    }
}
