package com.formacraft.common.build;

import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.UUID;

/**
 * 生成的结构
 * 代表生成器输出的一整个建筑
 */
public class GeneratedStructure {
    private final UUID owner;              // 玩家 UUID（可选）
    private final BlockPos origin;         // 参考点
    private final String description;      // 用于日志/调试
    private final List<PlannedBlock> blocks;

    public GeneratedStructure(UUID owner, BlockPos origin, String description, List<PlannedBlock> blocks) {
        this.owner = owner;
        this.origin = origin;
        this.description = description;
        this.blocks = List.copyOf(blocks);
    }

    public UUID getOwner() {
        return owner;
    }

    public BlockPos getOrigin() {
        return origin;
    }

    public String getDescription() {
        return description;
    }

    public List<PlannedBlock> getBlocks() {
        return blocks;
    }

    public int size() {
        return blocks.size();
    }
}
