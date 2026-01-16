package com.formacraft.common.llm.dto.structural;

import com.formacraft.common.geometry.Polygon2D;
import com.formacraft.common.geometry.Polygon3D;
import com.formacraft.common.geometry.Vec3;

/**
 * RoofSlope（屋顶坡面）
 * <p>
 * 定义屋顶的坡面几何
 * <p>
 * v2：使用 Polygon2D（XZ 平面投影）
 * v3：扩展支持 Polygon3D（3D 坡面）+ 关联脊线
 * <p>
 * 向后兼容：保留 v2 构造函数
 */
public class RoofSlope {
    /** 坡面区域（XZ 平面投影，v2） */
    public final Polygon2D area;

    /** 坡面区域（3D，v3） */
    public final Polygon3D area3D;

    /** 坡面法向（3D） */
    public final Vec3 normal;

    /** 坡度角（度） */
    public final double pitch;

    /** 关联的脊线（v3 新增：坡面被哪些脊线包围） */
    public final RidgeLine boundedBy;

    /**
     * v2 构造函数（向后兼容）
     */
    public RoofSlope(
            Polygon2D area,
            Vec3 normal,
            double pitch
    ) {
        this.area = area;
        this.normal = normal;
        this.pitch = pitch;
        this.area3D = null;
        this.boundedBy = null;
    }

    /**
     * v3 构造函数（3D 坡面 + 关联脊线）
     */
    public RoofSlope(
            Polygon3D area3D,
            Vec3 normal,
            double pitch,
            RidgeLine boundedBy
    ) {
        this.area3D = area3D;
        this.area = area3D != null ? area3D.projectToXZ() : null;
        this.normal = normal;
        this.pitch = pitch;
        this.boundedBy = boundedBy;
    }
}
