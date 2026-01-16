package com.formacraft.common.mass;

import com.formacraft.common.llm.dto.PlanSkeleton;
import com.formacraft.common.llm.dto.StructuralSkeleton;

/**
 * MassAssemblyExample（体量组合示例）
 * <p>
 * 提供体量组合的使用示例
 * <p>
 * 这是 Building Mass Assembly 的示例代码，展示如何使用体量组合系统
 */
public final class MassAssemblyExample {

    private MassAssemblyExample() {}

    /**
     * 示例 1：创建简单的单个体量组合
     * <p>
     * 这是 Phase 2 的基础示例
     */
    public static StructuralSkeleton exampleSimpleBlock(PlanSkeleton domain) {
        // 1. 验证 Domain
        PlanDomainValidator.ValidationResult validation = PlanDomainValidator.validate(domain);
        if (!validation.valid) {
            throw new IllegalArgumentException("Invalid Domain: " + validation.errorMessage);
        }

        // 2. 创建简单的体量组合（单个 BLOCK）
        MassAssembly assembly = MassAssemblyBuilder.createSimpleBlockAssembly(
                domain,
                "main_building",
                0, 0, 0,        // 位置（相对于 Domain 原点）
                20, 15, 10,     // 尺寸（宽、高、深）
                5,              // 5 层
                3.0             // 层高 3.0
        );

        // 3. 从体量组合派生 StructuralSkeleton
        return MassToStructuralDeriver.deriveFromMassAssembly(assembly);
    }

    /**
     * 示例 2：创建带悬挑阳台的体量组合
     * <p>
     * 这是 Phase 3 的示例
     */
    public static StructuralSkeleton exampleWithOverhangBalcony(PlanSkeleton domain) {
        // 1. 验证 Domain
        PlanDomainValidator.ValidationResult validation = PlanDomainValidator.validate(domain);
        if (!validation.valid) {
            throw new IllegalArgumentException("Invalid Domain: " + validation.errorMessage);
        }

        // 2. 创建主体 + 悬挑阳台
        MassAssembly assembly = MassAssemblyBuilder.createWithOverhangBalcony(
                domain,
                "main", "balcony",
                0, 0, 0,        // 主体位置
                20, 15, 10,     // 主体尺寸
                6, 10, 3,       // 阳台位置（悬挑）
                8, 3            // 阳台尺寸（宽、深）
        );

        // 3. 从体量组合派生 StructuralSkeleton
        return MassToStructuralDeriver.deriveFromMassAssembly(assembly);
    }

    /**
     * 示例 3：手动创建体量组合
     * <p>
     * 展示如何手动构建复杂的体量组合
     */
    public static StructuralSkeleton exampleManualAssembly(PlanSkeleton domain) {
        // 1. 验证 Domain
        PlanDomainValidator.ValidationResult validation = PlanDomainValidator.validate(domain);
        if (!validation.valid) {
            throw new IllegalArgumentException("Invalid Domain: " + validation.errorMessage);
        }

        // 2. 创建多个体量
        MassDefinition mainMass = MassBuilder.createBlock(
                "main", 0, 0, 0,
                20, 15, 10,
                5, 3.0
        );

        MassDefinition wingMass = MassBuilder.createBlock(
                "wing", 20, 0, 0,
                10, 12, 8,
                4, 3.0
        );

        // 3. 创建体量组合
        MassAssembly assembly = MassAssembly.empty(domain)
                .withMass(mainMass)
                .withMass(wingMass);

        // 4. 添加关系（附着）
        MassRelationship attachedRelation = new MassRelationship(
                MassRelationship.Type.ATTACHED,
                "main",
                "wing",
                new net.minecraft.util.math.Vec3i(20, 0, 0)
        );

        assembly = assembly.withRelationship(attachedRelation);

        // 5. 从体量组合派生 StructuralSkeleton
        return MassToStructuralDeriver.deriveFromMassAssembly(assembly);
    }
}
