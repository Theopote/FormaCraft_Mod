package com.formacraft.common.llm.dto.structural;

import com.formacraft.common.geometry.Line2D;
import com.formacraft.common.geometry.Line3D;

/**
 * RidgeLine（屋脊线）
 * <p>
 * 定义屋顶的脊线几何
 * <p>
 * v2：使用 Line2D（XZ 平面）+ heightY
 * v3：扩展支持 Line3D（3D 脊线）+ RidgeType
 * <p>
 * 向后兼容：保留 v2 构造函数
 */
public class RidgeLine {
    /** 脊线（XZ 平面，v2） */
    public final Line2D lineXZ;

    /** 脊线（3D，v3） */
    public final Line3D line3D;

    /** 脊线高度（Y 坐标，v2） */
    public final double heightY;

    /** 脊线角色 */
    public final RidgeRole role;

    /** 脊线类型（v3 新增） */
    public final RidgeType type;

    /**
     * v2 构造函数（向后兼容）
     */
    public RidgeLine(
            Line2D lineXZ,
            double heightY,
            RidgeRole role
    ) {
        this.lineXZ = lineXZ;
        this.heightY = heightY;
        this.role = role != null ? role : RidgeRole.SECONDARY;
        this.line3D = null;
        this.type = null;
    }

    /**
     * v3 构造函数（3D 脊线 + 类型）
     */
    public RidgeLine(
            Line3D line3D,
            RidgeType type,
            RidgeRole role
    ) {
        this.line3D = line3D;
        this.type = type != null ? type : RidgeType.MAIN_RIDGE;
        this.role = role != null ? role : RidgeRole.MAIN;
        this.lineXZ = line3D != null ? line3D.projectToXZ() : null;
        this.heightY = line3D != null ? line3D.midpoint().y() : 0.0;
    }
}
