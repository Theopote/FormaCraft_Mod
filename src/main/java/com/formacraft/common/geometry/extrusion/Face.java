package com.formacraft.common.geometry.extrusion;

import com.formacraft.common.geometry.Vec3;

import java.util.List;

/**
 * Face（面）
 * <p>
 * 表示一个多边形面（由顶点列表定义）
 * <p>
 * ⚠️ 注意：v1 不需要三角化，只要能表示面即可（调试 + Skeleton 都够）
 */
public class Face {
    /** 面的顶点（按顺序） */
    public final List<Vec3> vertices;

    /** 面法线（可选，用于渲染/碰撞检测） */
    public final Vec3 normal;

    public Face(List<Vec3> vertices) {
        this.vertices = vertices != null ? List.copyOf(vertices) : List.of();
        this.normal = computeNormal();
    }

    public Face(List<Vec3> vertices, Vec3 normal) {
        this.vertices = vertices != null ? List.copyOf(vertices) : List.of();
        this.normal = normal != null ? normal : computeNormal();
    }

    /**
     * 计算面的法线（使用前三个顶点）
     */
    private Vec3 computeNormal() {
        if (vertices.size() < 3) {
            return Vec3.ZERO;
        }

        Vec3 v0 = vertices.get(0);
        Vec3 v1 = vertices.get(1);
        Vec3 v2 = vertices.get(2);

        Vec3 edge1 = v1.subtract(v0);
        Vec3 edge2 = v2.subtract(v0);

        // 叉积
        double nx = edge1.y() * edge2.z() - edge1.z() * edge2.y();
        double ny = edge1.z() * edge2.x() - edge1.x() * edge2.z();
        double nz = edge1.x() * edge2.y() - edge1.y() * edge2.x();

        return new Vec3(nx, ny, nz).normalize();
    }

    /**
     * 获取顶点数量
     */
    public int vertexCount() {
        return vertices.size();
    }
}
