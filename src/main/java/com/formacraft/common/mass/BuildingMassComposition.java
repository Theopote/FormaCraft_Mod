package com.formacraft.common.mass;

import com.formacraft.common.llm.dto.PlanSkeleton;

import java.util.*;

/**
 * BuildingMassComposition（建筑体量组合）
 * <p>
 * 🎯 核心定位（架构校准 2026-01-14）：
 * 体量组合 ≠ 形状运算
 * 体量组合 = 多个体量在同一世界坐标系中，对"哪些方块允许存在"的规则叠加
 * <p>
 * 我们关心的是：
 * - 相对位置
 * - 相对高度
 * - 规则优先级
 * - 覆盖 / 让位 / 悬空
 * <p>
 * 而不是"布尔几何"。
 * <p>
 * 三种最小组合范式：
 * 1. 主体体量（Primary Mass）
 * 2. 侧向附着（Wing Attachment）
 * 3. 上部悬挑（Cantilever）
 */
public class BuildingMassComposition {
    /** Plan Domain（范围约束） */
    public final PlanSkeleton domain;

    /** 所有体量 */
    private final List<BuildingMass> masses;

    /** 翼楼附着关系（massId -> WingAttachment） */
    private final Map<String, WingAttachment> wingAttachments;

    /** 悬挑支撑关系（massId -> CantileverSupport） */
    private final Map<String, CantileverSupport> cantileverSupports;

    public BuildingMassComposition(
            PlanSkeleton domain,
            List<BuildingMass> masses,
            Map<String, WingAttachment> wingAttachments,
            Map<String, CantileverSupport> cantileverSupports
    ) {
        this.domain = domain;
        this.masses = masses != null ? new ArrayList<>(masses) : new ArrayList<>();
        this.wingAttachments = wingAttachments != null ? new HashMap<>(wingAttachments) : new HashMap<>();
        this.cantileverSupports = cantileverSupports != null ? new HashMap<>(cantileverSupports) : new HashMap<>();
    }

    /**
     * 创建一个空的体量组合
     */
    public static BuildingMassComposition empty(PlanSkeleton domain) {
        return new BuildingMassComposition(domain, List.of(), Map.of(), Map.of());
    }

    /**
     * 添加一个体量
     */
    public BuildingMassComposition withMass(BuildingMass mass) {
        List<BuildingMass> newMasses = new ArrayList<>(this.masses);
        newMasses.add(mass);
        return new BuildingMassComposition(this.domain, newMasses, this.wingAttachments, this.cantileverSupports);
    }

    /**
     * 添加翼楼附着关系
     */
    public BuildingMassComposition withWingAttachment(String massId, WingAttachment attachment) {
        Map<String, WingAttachment> newAttachments = new HashMap<>(this.wingAttachments);
        newAttachments.put(massId, attachment);
        return new BuildingMassComposition(this.domain, this.masses, newAttachments, this.cantileverSupports);
    }

    /**
     * 添加悬挑支撑关系
     */
    public BuildingMassComposition withCantileverSupport(String massId, CantileverSupport support) {
        Map<String, CantileverSupport> newSupports = new HashMap<>(this.cantileverSupports);
        newSupports.put(massId, support);
        return new BuildingMassComposition(this.domain, this.masses, this.wingAttachments, newSupports);
    }

    /**
     * 获取按优先级排序的体量列表
     * <p>
     * 统一的体量叠加顺序（非常重要）：
     * 1. PRIMARY
     * 2. SECONDARY
     * 3. CANTILEVER
     * 4. SUBTRACT（中庭、空洞）
     * <p>
     * 这一个顺序，能避免 80% 的冲突问题。
     */
    public List<BuildingMass> getOrderedMasses() {
        List<BuildingMass> ordered = new ArrayList<>(masses);
        
        // 按优先级排序
        ordered.sort((a, b) -> {
            // 1. PRIMARY 优先
            if (a.role == MassRole.PRIMARY && b.role != MassRole.PRIMARY) return -1;
            if (a.role != MassRole.PRIMARY && b.role == MassRole.PRIMARY) return 1;
            
            // 2. SECONDARY 次之
            if (a.role == MassRole.SECONDARY && b.role == MassRole.CANTILEVER) return -1;
            if (a.role == MassRole.CANTILEVER && b.role == MassRole.SECONDARY) return 1;
            
            // 3. CANTILEVER 再次
            if (a.role == MassRole.CANTILEVER && b.role != MassRole.CANTILEVER) {
                if (b.operation == MassOperation.SUBTRACT) return -1;
                return -1;
            }
            if (a.role != MassRole.CANTILEVER && b.role == MassRole.CANTILEVER) {
                if (a.operation == MassOperation.SUBTRACT) return 1;
                return 1;
            }
            
            // 4. SUBTRACT 最后
            if (a.operation == MassOperation.SUBTRACT && b.operation != MassOperation.SUBTRACT) return 1;
            if (a.operation != MassOperation.SUBTRACT && b.operation == MassOperation.SUBTRACT) return -1;
            
            return 0;
        });
        
        return ordered;
    }

    /**
     * 判断在指定位置是否允许放置方块
     * <p>
     * 世界中某点 (x,y,z) 的最终判定：
     * <p>
     * 按照 orderedMasses 顺序判断：
     * - ADD -> filled = true
     * - SUBTRACT -> filled = false
     * <p>
     * ⚠️ 关键：
     * - 对于悬挑体量，需要检查支撑关系
     * - 对于翼楼体量，需要检查附着关系
     *
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @return 是否允许放置方块
     */
    public boolean allowsBlockAt(int x, int y, int z) {
        boolean filled = false;
        List<BuildingMass> orderedMasses = getOrderedMasses();

        for (BuildingMass mass : orderedMasses) {
            if (!mass.footprint.contains(x, z) || !mass.height.contains(y)) {
                continue; // 不在这个体量的范围内
            }

            // 特殊检查：悬挑体量需要检查支撑关系
            if (mass.role == MassRole.CANTILEVER) {
                CantileverSupport support = cantileverSupports.get(mass.id);
                if (support != null && !checkCantileverSupport(x, y, z, mass, support)) {
                    continue; // 悬挑距离超出限制，不允许
                }
            }

            // 特殊检查：翼楼体量需要检查附着关系
            if (mass.role == MassRole.SECONDARY) {
                WingAttachment attachment = wingAttachments.get(mass.id);
                if (attachment != null && !checkWingAttachment(x, y, z, mass, attachment)) {
                    continue; // 翼楼附着关系不满足，不允许
                }
            }

            // 根据 operation 决定
            switch (mass.operation) {
                case ADD -> filled = true;
                case SUBTRACT -> filled = false;
                case INTERSECT -> {
                    // INTERSECT 需要所有 INTERSECT 体量都包含这个位置
                    // v1 简化：暂不处理
                }
            }
        }

        return filled;
    }

    /**
     * 检查悬挑支撑关系
     * <p>
     * 规则：悬挑距离不能超过 maxOverhang
     */
    private boolean checkCantileverSupport(int x, int y, int z, BuildingMass cantilever, CantileverSupport support) {
        // 找到支撑体量
        BuildingMass supportMass = masses.stream()
                .filter(m -> m.id.equals(support.supportMassId))
                .findFirst()
                .orElse(null);

        if (supportMass == null) {
            return false; // 支撑体量不存在
        }

        // 计算距离支撑体量的距离（简化：XZ 平面距离）
        // v1 简化：只检查是否在支撑体量上方
        if (y > supportMass.height.topY) {
            // v1 简化：暂时允许所有悬挑（后续可以细化距离判断）
            // 未来：需要计算 (x, z) 到支撑体量边界的距离，检查是否 <= maxOverhang
            return true;
        }

        return false;
    }

    /**
     * 检查翼楼附着关系
     * <p>
     * 规则：翼楼必须与主体至少有一条立面相交
     */
    private boolean checkWingAttachment(int x, int y, int z, BuildingMass wing, WingAttachment attachment) {
        // 找到主体
        BuildingMass host = masses.stream()
                .filter(m -> m.id.equals(attachment.hostMassId))
                .findFirst()
                .orElse(null);

        if (host == null) {
            return false; // 主体不存在
        }

        // v1 简化：检查翼楼与主体是否有 footprint 重叠
        // 未来：需要更精确的几何检测
        // 暂时允许所有翼楼附着
        return true;
    }

    /**
     * 获取所有体量（不可修改）
     */
    public List<BuildingMass> getMasses() {
        return Collections.unmodifiableList(masses);
    }
}
