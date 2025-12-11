package com.formacraft.server.terrain;

import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.PlannedBlock;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.UUID;

/**
 * 地形整形操作
 * 封装一组 PlannedBlock，用于描述地形修改
 */
public class TerrainOperation {
    private final List<PlannedBlock> blocks;

    public TerrainOperation(List<PlannedBlock> blocks) {
        this.blocks = blocks;
    }

    public List<PlannedBlock> getBlocks() {
        return blocks;
    }

    /**
     * 转换为 GeneratedStructure
     */
    public GeneratedStructure toGeneratedStructure(BlockPos origin, String description) {
        return new GeneratedStructure(
                UUID.randomUUID(),
                origin,
                description,
                blocks
        );
    }
}

