package com.formacraft.common.mass;

import net.minecraft.util.math.Vec3i;

/**
 * MassRelationship（体量关系）
 * <p>
 * 定义两个体量之间的关系，用于 Building Mass Assembly
 * <p>
 * 这是体量组合的核心：描述体量如何相互关联（附着、穿插、悬挑、错动）
 */
public class MassRelationship {
    /** 关系类型 */
    public final Type type;

    /** 第一个体量的 ID */
    public final String massA;

    /** 第二个体量的 ID */
    public final String massB;

    /** 相对偏移（massB 相对于 massA 的偏移） */
    public final Vec3i offset;

    public MassRelationship(
            Type type,
            String massA,
            String massB,
            Vec3i offset
    ) {
        this.type = type != null ? type : Type.ATTACHED;
        this.massA = massA;
        this.massB = massB;
        this.offset = offset != null ? offset : Vec3i.ZERO;
    }

    /**
     * 关系类型
     */
    public enum Type {
        /**
         * 附着（ATTACHED）
         * <p>
         * 两个体量共用面，但不相交
         * <p>
         * 示例：
         * - 主楼和侧翼
         * - 两个相邻的房间
         */
        ATTACHED,

        /**
         * 穿插（INTERSECT）
         * <p>
         * 两个体量的体积有重叠
         * <p>
         * 示例：
         * - 十字形建筑
         * - T 形组合
         */
        INTERSECT,

        /**
         * 悬挑（OVERHANG）
         * <p>
         * 一个体量部分悬空，附着在另一个体量上
         * <p>
         * 示例：
         * - 悬挑阳台
         * - 挑出的檐廊
         */
        OVERHANG,

        /**
         * 错动（OFFSET）
         * <p>
         * 两个体量在位置上错开，可能有高度差
         * <p>
         * 示例：
         * - 错层建筑
         * - 阶梯式体量
         */
        OFFSET
    }
}
