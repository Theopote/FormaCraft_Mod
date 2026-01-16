package com.formacraft.common.mass;

import com.formacraft.common.llm.dto.PlanSkeleton;

/**
 * MassAssemblyBuilder（体量组合构建器）
 * <p>
 * 提供便捷的方法来构建 MassAssembly
 * <p>
 * 这是 Building Mass Assembly 的工具类，用于简化体量组合的创建
 */
public final class MassAssemblyBuilder {

    private MassAssemblyBuilder() {}

    /**
     * 创建一个简单的体量组合（单个 BLOCK）
     * <p>
     * v1 简化：创建最简单的体量组合（单个方盒子）
     * <p>
     * 这是 Phase 2 的基础实现
     *
     * @param domain Plan Domain
     * @param id 体量 ID
     * @param x 在 Domain 内的 X 坐标
     * @param y 在 Domain 内的 Y 坐标
     * @param z 在 Domain 内的 Z 坐标
     * @param width 宽度
     * @param height 高度
     * @param depth 深度
     * @param floorCount 层数
     * @param floorHeight 层高
     * @return MassAssembly
     */
    public static MassAssembly createSimpleBlockAssembly(
            PlanSkeleton domain,
            String id,
            int x, int y, int z,
            int width, int height, int depth,
            int floorCount,
            double floorHeight
    ) {
        // 验证 Domain
        PlanDomainValidator.ValidationResult validation = PlanDomainValidator.validate(domain);
        if (!validation.valid) {
            throw new IllegalArgumentException("Invalid Plan Domain: " + validation.errorMessage);
        }

        // 创建单个 BLOCK 体量
        MassDefinition mass = MassBuilder.createBlock(
                id, x, y, z,
                width, height, depth,
                floorCount, floorHeight
        );

        // 创建体量组合（单个体量，无关系）
        return MassAssembly.empty(domain)
                .withMass(mass);
    }

    /**
     * 创建一个带悬挑阳台的体量组合
     * <p>
     * 示例场景：主体 + 悬挑阳台
     * <p>
     * 这是 Phase 3 的示例实现
     *
     * @param domain Plan Domain
     * @param mainMassId 主体 ID
     * @param balconyMassId 阳台 ID
     * @param mainX 主体 X 坐标
     * @param mainY 主体 Y 坐标
     * @param mainZ 主体 Z 坐标
     * @param mainWidth 主体宽度
     * @param mainHeight 主体高度
     * @param mainDepth 主体深度
     * @param balconyX 阳台 X 坐标（悬挑位置）
     * @param balconyY 阳台 Y 坐标（通常高于主体底部）
     * @param balconyZ 阳台 Z 坐标
     * @param balconyWidth 阳台宽度
     * @param balconyDepth 阳台深度
     * @return MassAssembly
     */
    public static MassAssembly createWithOverhangBalcony(
            PlanSkeleton domain,
            String mainMassId,
            String balconyMassId,
            int mainX, int mainY, int mainZ,
            int mainWidth, int mainHeight, int mainDepth,
            int balconyX, int balconyY, int balconyZ,
            int balconyWidth, int balconyDepth
    ) {
        // 验证 Domain
        PlanDomainValidator.ValidationResult validation = PlanDomainValidator.validate(domain);
        if (!validation.valid) {
            throw new IllegalArgumentException("Invalid Plan Domain: " + validation.errorMessage);
        }

        // 创建主体（BLOCK）
        MassDefinition mainMass = MassBuilder.createBlock(
                mainMassId, mainX, mainY, mainZ,
                mainWidth, mainHeight, mainDepth,
                (int) Math.ceil(mainHeight / 3.0), // 估算层数
                3.0 // 默认层高
        );

        // 创建阳台（SLAB）
        MassDefinition balconyMass = MassBuilder.createSlab(
                balconyMassId, balconyX, balconyY, balconyZ,
                balconyWidth, 1, balconyDepth // 阳台厚度 = 1
        );

        // 创建体量组合
        MassAssembly assembly = MassAssembly.empty(domain)
                .withMass(mainMass)
                .withMass(balconyMass);

        // 添加悬挑关系
        net.minecraft.util.math.Vec3i offset = new net.minecraft.util.math.Vec3i(
                balconyX - mainX,
                balconyY - mainY,
                balconyZ - mainZ
        );

        MassRelationship overhangRelation = new MassRelationship(
                MassRelationship.Type.OVERHANG,
                mainMassId,
                balconyMassId,
                offset
        );

        return assembly.withRelationship(overhangRelation);
    }
}
