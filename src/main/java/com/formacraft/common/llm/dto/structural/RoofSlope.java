package com.formacraft.common.llm.dto.structural;

import com.formacraft.common.geometry.Polygon2D;
import com.formacraft.common.geometry.Vec3;

/**
 * RoofSlope（屋顶坡面）
 * <p>
 * 定义屋顶的坡面几何
 */
public class RoofSlope {
    /** 坡面区域（XZ 平面投影） */
    public final Polygon2D area;

    /** 坡面法向（3D） */
    public final Vec3 normal;

    /** 坡度角（度） */
    public final double pitch;

    public RoofSlope(
            Polygon2D area,
            Vec3 normal,
            double pitch
    ) {
        this.area = area;
        this.normal = normal;
        this.pitch = pitch;
    }
}
