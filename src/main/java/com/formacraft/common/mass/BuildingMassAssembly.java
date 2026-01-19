package com.formacraft.common.mass;

import com.formacraft.common.llm.dto.PlanSkeleton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * BuildingMassAssembly（建筑体量组合）
 * <p>
 * 🎯 核心定位（架构校准 2026-01-14）：
 * 这不是几何组合，而是"规则组合"。
 * <p>
 * ⚠️ 注意：
 * 这个类已被 {@link BuildingMassComposition} 替代。
 * <p>
 * BuildingMassAssembly 是早期版本的体量组合系统，功能已被 BuildingMassComposition 完全覆盖。
 * BuildingMassComposition 提供了更强大的功能：
 * - 支持 WingAttachment（翼楼附着）
 * - 支持 CantileverSupport（悬挑支撑）
 * - 提供 getOrderedMasses() 方法（按优先级排序）
 * <p>
 * 建议：
 * - 新代码应使用 {@link BuildingMassComposition}
 * - 这个类保留仅供向后兼容，未来可能标记为 @Deprecated
 */
@Deprecated(forRemoval = false) // 暂时不删除，保留向后兼容
public class BuildingMassAssembly {
    /** Plan Domain（范围约束） */
    public final PlanSkeleton domain;

    /** 体量列表 */
    public final List<BuildingMass> masses;

    public BuildingMassAssembly(
            PlanSkeleton domain,
            List<BuildingMass> masses
    ) {
        this.domain = domain;
        this.masses = masses != null ? List.copyOf(masses) : Collections.emptyList();
    }

    /**
     * 创建一个空的体量组合（只有 Domain，没有体量）
     */
    public static BuildingMassAssembly empty(PlanSkeleton domain) {
        return new BuildingMassAssembly(domain, List.of());
    }

    /**
     * 添加一个体量
     */
    public BuildingMassAssembly withMass(BuildingMass mass) {
        List<BuildingMass> newMasses = new ArrayList<>(this.masses);
        newMasses.add(mass);
        return new BuildingMassAssembly(this.domain, newMasses);
    }

    /**
     * 判断在指定位置是否允许放置方块
     * <p>
     * 这是 BuildingMassAssembly 的核心判断逻辑：
     * 遍历所有体量，根据 operation 规则判断是否允许放置方块
     * <p>
     * 判断规则：
     * - ADD：如果任意 ADD 体量包含 (x, y, z)，则允许
     * - SUBTRACT：如果任意 SUBTRACT 体量包含 (x, y, z)，则不允许
     * - INTERSECT：只有在所有 INTERSECT 体量都包含 (x, y, z) 时，才允许
     * <p>
     * ⚠️ 注意：这是离散的方块位置判断，完全贴合 Minecraft 的方块世界
     *
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @return 是否允许放置方块
     */
    public boolean allowsBlockAt(int x, int y, int z) {
        boolean filled = false;
        boolean hasIntersect = false;

        for (BuildingMass mass : masses) {
            if (!mass.footprint.contains(x, z) || !mass.height.contains(y)) {
                continue; // 不在这个体量的范围内
            }

            switch (mass.operation) {
                case ADD -> filled = true;
                case SUBTRACT -> {
                    // 相减：直接返回 false
                    return false;
                }
                case INTERSECT -> // INTERSECT 需要所有 INTERSECT 体量都包含这个位置
                    // 这里只标记，最后再判断
                        hasIntersect = true;
            }
        }

        // 如果有 INTERSECT 体量，需要检查是否所有 INTERSECT 体量都包含这个位置
        if (hasIntersect) {
            boolean allIntersectContain = true;
            for (BuildingMass mass : masses) {
                if (mass.operation == MassOperation.INTERSECT) {
                    if (!mass.footprint.contains(x, z) || !mass.height.contains(y)) {
                        allIntersectContain = false;
                        break;
                    }
                }
            }
            if (!allIntersectContain) {
                return false; // 不是所有 INTERSECT 体量都包含这个位置
            }
        }

        return filled || hasIntersect;
    }
}
