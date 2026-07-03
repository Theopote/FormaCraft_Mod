package com.formacraft.common.generation.structure;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.build.GeneratedStructure;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * 建筑生成器统一接口
 * 将 BuildingSpec 转换为 PlannedBlock 列表
 */
public interface StructureGenerator {
    /**
     * 根据建筑规格生成结构
     * @param spec 建筑规格
     * @param origin 建造原点
     * @param world 服务器世界
     * @return 生成的结构（包含所有计划放置的方块）
     */
    GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world);
}

