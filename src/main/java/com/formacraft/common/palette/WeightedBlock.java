package com.formacraft.common.palette;

/**
 * 加权方块（用于随机选择）
 */
public record WeightedBlock(String blockId, int weight) {
    public WeightedBlock {
        if (blockId == null) throw new IllegalArgumentException("blockId cannot be null");
        if (weight < 0) throw new IllegalArgumentException("weight cannot be negative");
    }
}

