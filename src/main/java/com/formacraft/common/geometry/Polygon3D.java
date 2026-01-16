package com.formacraft.common.geometry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Polygon3D（3D 多边形）
 * <p>
 * 用于表示 3D 空间中的多边形（坡面等）
 * <p>
 * v3 引入：支持 3D 坡面
 */
public class Polygon3D {
    private final List<Vec3> vertices;

    public Polygon3D(List<Vec3> vertices) {
        this.vertices = vertices != null && !vertices.isEmpty()
                ? new ArrayList<>(vertices)
                : new ArrayList<>();
    }

    /**
     * 获取所有顶点
     */
    public List<Vec3> getVertices() {
        return Collections.unmodifiableList(vertices);
    }

    /**
     * 获取顶点数量
     */
    public int vertexCount() {
        return vertices.size();
    }

    /**
     * 投影到 XZ 平面（返回 Polygon2D）
     */
    public Polygon2D projectToXZ() {
        List<Vec2> projected = new ArrayList<>();
        for (Vec3 v : vertices) {
            projected.add(new Vec2(v.x(), v.z()));
        }
        return new Polygon2D(projected);
    }
}
