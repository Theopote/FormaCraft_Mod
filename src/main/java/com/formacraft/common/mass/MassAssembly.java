package com.formacraft.common.mass;

import com.formacraft.common.llm.dto.PlanSkeleton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MassAssembly（体量组合）
 * <p>
 * 这是 Formacraft 真正缺失的核心中间层。
 * <p>
 * 核心职责：
 * 1. 在 Plan（Site Boundary / Domain）范围内
 * 2. 通过多个几何体的位移、错动、穿插、悬挑
 * 3. 组合成建筑体量
 * <p>
 * 正确的生成流程：
 * ```
 * PlanSkeleton (Domain)
 *   ↓
 * MassAssembly (体量组合)
 *   ↓
 * StructuralSkeleton (从体量组合派生)
 * ```
 */
public class MassAssembly {
    /** Plan Domain（范围约束） */
    public final PlanSkeleton domain;

    /** 体量列表 */
    public final List<MassDefinition> masses;

    /** 体量间关系 */
    public final List<MassRelationship> relationships;

    public MassAssembly(
            PlanSkeleton domain,
            List<MassDefinition> masses,
            List<MassRelationship> relationships
    ) {
        this.domain = domain;
        this.masses = masses != null ? Collections.unmodifiableList(new ArrayList<>(masses)) : Collections.emptyList();
        this.relationships = relationships != null ? Collections.unmodifiableList(new ArrayList<>(relationships)) : Collections.emptyList();
    }

    /**
     * 创建一个空的体量组合（只有 Domain，没有体量）
     */
    public static MassAssembly empty(PlanSkeleton domain) {
        return new MassAssembly(domain, List.of(), List.of());
    }

    /**
     * 添加一个体量
     */
    public MassAssembly withMass(MassDefinition mass) {
        List<MassDefinition> newMasses = new ArrayList<>(this.masses);
        newMasses.add(mass);
        return new MassAssembly(this.domain, newMasses, this.relationships);
    }

    /**
     * 添加一个关系
     */
    public MassAssembly withRelationship(MassRelationship relationship) {
        List<MassRelationship> newRelationships = new ArrayList<>(this.relationships);
        newRelationships.add(relationship);
        return new MassAssembly(this.domain, this.masses, newRelationships);
    }
}
