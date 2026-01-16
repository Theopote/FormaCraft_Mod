package com.formacraft.common.llm.dto.structural;

import com.formacraft.common.geometry.Line2D;

/**
 * RidgeLine（屋脊线）
 * <p>
 * 定义屋顶的脊线几何
 */
public class RidgeLine {
    /** 脊线（XZ 平面） */
    public final Line2D lineXZ;

    /** 脊线高度（Y 坐标） */
    public final double heightY;

    /** 脊线角色 */
    public final RidgeRole role;

    public RidgeLine(
            Line2D lineXZ,
            double heightY,
            RidgeRole role
    ) {
        this.lineXZ = lineXZ;
        this.heightY = heightY;
        this.role = role != null ? role : RidgeRole.SECONDARY;
    }
}
