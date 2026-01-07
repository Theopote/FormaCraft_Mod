package com.formacraft.common.palette.component;

/**
 * PaletteBlock（带权重的方块）
 * 
 * 用于 ComponentGenerator 的简化版权重方块
 */
public record PaletteBlock(
        String blockId,
        int weight
) {
    public PaletteBlock {
        if (blockId == null || blockId.isBlank()) {
            throw new IllegalArgumentException("blockId cannot be null or blank");
        }
        if (weight < 0) {
            throw new IllegalArgumentException("weight cannot be negative");
        }
    }
}

