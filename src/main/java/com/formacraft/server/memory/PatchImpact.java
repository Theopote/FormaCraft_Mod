package com.formacraft.server.memory;

import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Patch 影响记录：记录一次 Patch 操作对建筑的影响
 */
public class PatchImpact {
    /**
     * 影响类型
     */
    public enum ImpactType {
        /** 新建建筑 */
        CREATE,
        
        /** 修改已有建筑 */
        MODIFY,
        
        /** 扩建已有建筑 */
        EXTEND,
        
        /** 拆除建筑 */
        REMOVE
    }
    
    /** 目标建筑 UUID（null 表示新建筑） */
    private UUID targetBuilding;
    
    /** 影响类型 */
    private ImpactType type;
    
    /** 受影响的边界（AABB） */
    private BlockPos minPos;
    private BlockPos maxPos;
    
    /** 受影响的语义部位 */
    private Set<SemanticPart> affectedParts;
    
    public PatchImpact() {
        this.affectedParts = new HashSet<>();
    }
    
    public PatchImpact(UUID targetBuilding, ImpactType type) {
        this();
        this.targetBuilding = targetBuilding;
        this.type = type;
    }
    
    /**
     * 扩展边界以包含新位置
     */
    public void expandBounds(BlockPos pos) {
        if (pos == null) return;
        
        if (minPos == null || maxPos == null) {
            minPos = pos;
            maxPos = pos;
            return;
        }
        
        minPos = new BlockPos(
            Math.min(minPos.getX(), pos.getX()),
            Math.min(minPos.getY(), pos.getY()),
            Math.min(minPos.getZ(), pos.getZ())
        );
        
        maxPos = new BlockPos(
            Math.max(maxPos.getX(), pos.getX()),
            Math.max(maxPos.getY(), pos.getY()),
            Math.max(maxPos.getZ(), pos.getZ())
        );
    }
    
    /**
     * 获取边界中心点（用于判断是否扩建）
     */
    public BlockPos getCenter() {
        if (minPos == null || maxPos == null) {
            return null;
        }
        return new BlockPos(
            (minPos.getX() + maxPos.getX()) / 2,
            (minPos.getY() + maxPos.getY()) / 2,
            (minPos.getZ() + maxPos.getZ()) / 2
        );
    }
    
    // Getters and Setters
    public UUID getTargetBuilding() {
        return targetBuilding;
    }
    
    public void setTargetBuilding(UUID targetBuilding) {
        this.targetBuilding = targetBuilding;
    }
    
    public ImpactType getType() {
        return type;
    }
    
    public void setType(ImpactType type) {
        this.type = type;
    }
    
    public BlockPos getMinPos() {
        return minPos;
    }
    
    public void setMinPos(BlockPos minPos) {
        this.minPos = minPos;
    }
    
    public BlockPos getMaxPos() {
        return maxPos;
    }
    
    public void setMaxPos(BlockPos maxPos) {
        this.maxPos = maxPos;
    }
    
    public Set<SemanticPart> getAffectedParts() {
        return affectedParts;
    }
    
    public void setAffectedParts(Set<SemanticPart> affectedParts) {
        this.affectedParts = affectedParts != null ? affectedParts : new HashSet<>();
    }
    
    public void addAffectedPart(SemanticPart part) {
        if (part != null) {
            affectedParts.add(part);
        }
    }
}

