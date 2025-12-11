package com.formacraft.client.preview;

import net.minecraft.util.math.BlockPos;

/**
 * 预览线框方块
 * 用于客户端渲染建筑轮廓
 */
public class OutlineBlock {
    public final BlockPos pos;

    public OutlineBlock(BlockPos pos) {
        this.pos = pos;
    }
}

