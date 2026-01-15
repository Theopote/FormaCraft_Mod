package com.formacraft.common.component.socket;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/**
 * Socket（插槽）：几何 + 语义复合体。
 * <p>
 * 核心思想：
 * - Socket ≠ 点
 * - Socket 是一个几何 + 语义复合体
 * - normal 的真正含义：不是"朝向"，而是"外侧 or 内侧"
 * <p>
 * 这正好对应：
 * - 门窗只有"内 / 外"
 * - 而不是"东门 / 西门"
 */
public final class Socket {
    /** Socket ID（唯一标识） */
    public final String id;

    /** Socket 类型 */
    public SocketType type;

    /** Socket 所在的世界坐标区域（world-space bounds，double，适合渲染与 hit test） */
    public Box bounds;

    /** "外法线"（用于 exterior/interior 判断、对齐） */
    public Direction normal;

    /** 可选：沿边缘的切线方向（栏杆沿边布置） */
    public Direction tangent;

    /** 所属建筑语义上下文 */
    public SemanticContext context;

    /** 是否被占用 */
    public boolean occupied = false;

    /**
     * 创建 Socket（v1 简化版，兼容旧代码）
     */
    public Socket(SocketType type, Box bounds, Direction normal, SemanticContext context) {
        this(UUID.randomUUID().toString(), type, bounds, normal, null, context);
    }

    /**
     * 创建 Socket（完整版，包含 tangent）
     */
    public Socket(String id, SocketType type, Box bounds, Direction normal, Direction tangent, SemanticContext context) {
        this.id = id != null ? id : UUID.randomUUID().toString();
        this.type = type;
        this.bounds = bounds;
        this.normal = normal;
        this.tangent = tangent;
        this.context = context;
    }

    /**
     * 创建 Socket（v1 简化版，无 context）
     */
    public Socket(SocketType type, Box bounds, Direction normal, Direction tangent) {
        this(UUID.randomUUID().toString(), type, bounds, normal, tangent, null);
    }

    /**
     * 获取中心点
     */
    public Vec3d center() {
        return bounds.getCenter();
    }

    /**
     * 获取中心点（BlockPos）
     */
    public BlockPos centerBlockPos() {
        Vec3d c = center();
        return BlockPos.ofFloored(c.x, c.y, c.z);
    }

    /**
     * 检查是否在外侧
     */
    public boolean isExterior() {
        // 简化处理：根据 normal 的方向判断
        // 如果 normal 指向外部（例如：NORTH 表示墙面向北，外侧在北），则为 exterior
        // 这里需要根据实际建筑语义来判断
        return context != null && context.isExterior;
    }

    /**
     * 语义上下文
     */
    public static class SemanticContext {
        /** 是否在外侧 */
        public boolean isExterior = false;

        /** 建筑语义标签（例如：main_wall, side_wall, entrance） */
        public String semanticTag;

        /** 所属建筑 ID（如果有） */
        public String buildingId;

        public SemanticContext(boolean isExterior, String semanticTag) {
            this.isExterior = isExterior;
            this.semanticTag = semanticTag;
        }
    }
}
