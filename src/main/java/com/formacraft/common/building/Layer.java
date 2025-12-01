package com.formacraft.common.building;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import java.util.HashMap;
import java.util.Map;

public class Layer {
    private final int yLevel;
    private final Map<BlockPos, BlockState> blocks;
    private final LayerType type;
    
    public enum LayerType {
        FOUNDATION,
        WALLS,
        INTERIOR,
        DECORATION,
        ROOF
    }
    
    public Layer(int yLevel, LayerType type) {
        this.yLevel = yLevel;
        this.type = type;
        this.blocks = new HashMap<>();
    }
    
    public void addBlock(BlockPos relativePos, BlockState state) {
        this.blocks.put(relativePos, state);
    }
    
    public int getYLevel() {
        return yLevel;
    }
    
    public Map<BlockPos, BlockState> getBlocks() {
        return new HashMap<>(blocks);
    }
    
    public LayerType getType() {
        return type;
    }
}
