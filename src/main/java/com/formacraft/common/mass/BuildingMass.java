package com.formacraft.common.mass;

/**
 * BuildingMass（建筑体量）
 * <p>
 * 🎯 核心定位（架构校准 2026-01-14）：
 * BuildingMass 不是几何体，不是模型，不是 mesh。
 * BuildingMass 是"在某个空间域内，允许方块生成的一段体量规则集合"。
 * <p>
 * 换成 Minecraft 语言就是：
 * BuildingMass = 一块"可以放哪些方块、放到多高、哪些地方允许/不允许放"的体积规则。
 * <p>
 * 核心职责（只回答 5 个问题）：
 * 1. 范围：我在哪个区域内生效？（footprint）
 * 2. 高度：我在 Y 方向上允许多高？（height）
 * 3. 形态：我是实心的？中空的？板状的？（type）
 * 4. 关系：我和其他体量是叠加、穿插、相减？（operation）
 * 5. 用途倾向：我是主体？附属？挑出？（role）
 * <p>
 * 它不回答：
 * - 用什么方块
 * - 有没有窗
 * - 屋顶怎么做
 * - 立面长什么样
 * <p>
 * ⚠️ 关键设计：
 * - ✅ 离散的（基于方块位置）
 * - ✅ 贴合 Minecraft 的方块世界
 * - ✅ 没有连续几何运算
 * - ✅ 一切以 BlockPos 为中心
 */
public class BuildingMass {
    /** 体量唯一标识 */
    public final String id;

    /** 体量生效的水平范围（XZ）- 离散的方块位置判断 */
    public final AreaMask footprint;

    /** 垂直范围（Y）- 离散的整数范围 */
    public final HeightRange height;

    /** 体量类型（填充方式） */
    public final MassType type;

    /** 与其他体量的组合关系 */
    public final MassOperation operation;

    /** 语义角色（给 AI / 后续规则用） */
    public final MassRole role;

    public BuildingMass(
            String id,
            AreaMask footprint,
            HeightRange height,
            MassType type,
            MassOperation operation,
            MassRole role
    ) {
        this.id = id != null ? id : "mass_" + System.nanoTime();
        this.footprint = footprint;
        this.height = height;
        this.type = type != null ? type : MassType.SOLID;
        this.operation = operation != null ? operation : MassOperation.ADD;
        this.role = role != null ? role : MassRole.PRIMARY;
    }

    /**
     * 判断在指定位置是否允许放置方块
     * <p>
     * 这是 BuildingMass 的核心判断逻辑：
     * - 检查 footprint 是否包含 (x, z)
     * - 检查 height 是否包含 y
     * - 根据 operation 决定是否允许
     * <p>
     * ⚠️ 注意：这是离散的方块位置判断，没有连续几何运算
     *
     * @param x X 坐标
     * @param y Y 坐标
     * @param z Z 坐标
     * @return 是否允许放置方块
     */
    public boolean allowsBlockAt(int x, int y, int z) {
        // 检查是否在 footprint 和 height 范围内
        if (!footprint.contains(x, z) || !height.contains(y)) {
            return false;
        }

        // 根据 operation 决定
        return switch (operation) {
            case ADD -> true;           // 叠加：允许
            case SUBTRACT -> false;     // 相减：不允许
            case INTERSECT -> true;     // 穿插：需要与其他体量一起判断（在外层处理）
        };
    }
}
