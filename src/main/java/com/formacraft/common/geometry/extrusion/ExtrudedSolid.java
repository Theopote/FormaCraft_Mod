package com.formacraft.common.geometry.extrusion;

import com.formacraft.common.geometry.Vec3;

import java.util.List;

/**
 * ExtrudedSolid（拉伸体）
 * <p>
 * 表示通过 extrusion 生成的 3D 几何体
 * <p>
 * 供 Skeleton 使用
 */
public class ExtrudedSolid {
    /** 所有顶点 */
    public final List<Vec3> vertices;

    /** 所有面 */
    public final List<Face> faces;

    public ExtrudedSolid(List<Vec3> vertices, List<Face> faces) {
        this.vertices = vertices != null ? List.copyOf(vertices) : List.of();
        this.faces = faces != null ? List.copyOf(faces) : List.of();
    }

    /**
     * 是否是空的（无顶点或面无）
     */
    public boolean isEmpty() {
        return vertices.isEmpty() || faces.isEmpty();
    }

    /**
     * 获取顶点数量
     */
    public int vertexCount() {
        return vertices.size();
    }

    /**
     * 获取面数量
     */
    public int faceCount() {
        return faces.size();
    }
}
