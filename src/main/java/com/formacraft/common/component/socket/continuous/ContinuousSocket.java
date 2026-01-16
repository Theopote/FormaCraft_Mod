package com.formacraft.common.component.socket.continuous;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * ContinuousSocket（连续插槽）：用于连续放置构件的路径/边界/折线/环。
 * <p>
 * 这是 H4 的核心抽象，将"一条路径"抽象为可采样的连续插槽。
 * <p>
 * 常见实现：
 * - PathSocket（来自 PathTool）
 * - OutlineEdgeSocket（来自 OutlineTool 的边界）
 * - RingSocket（圆形/环形）
 * - PolylineSocket（任意折线）
 */
public interface ContinuousSocket {
    /**
     * 离散后的采样点（世界坐标）
     * 
     * @param step 采样步长（方块单位，通常为 1）
     * @return 采样点列表
     */
    List<BlockPos> samplePoints(int step);

    /**
     * 法线方向（用于朝向/外侧）
     * 
     * @param index 采样点索引
     * @return 该点的法线方向（Vec3d，归一化）
     */
    Vec3d normalAt(int index);

    /**
     * 切线方向（用于沿边缘布置）
     * 
     * @param index 采样点索引
     * @return 该点的切线方向（Vec3d，归一化）
     */
    Vec3d tangentAt(int index);

    /**
     * 是否闭合（环）
     * 
     * @return true 如果路径是闭合的
     */
    boolean isClosed();

    /**
     * 原始几何（可选，用于高级处理）
     * 
     * @return 原始几何对象（例如 PathTool.Path, OutlineTool.OutlineShape）
     */
    default Object rawGeometry() {
        return null;
    }

    /**
     * 获取路径总长度（方块单位，近似）
     * 
     * @return 路径总长度
     */
    default double getTotalLength() {
        List<BlockPos> points = samplePoints(1);
        if (points.size() < 2) return 0.0;

        double total = 0.0;
        for (int i = 1; i < points.size(); i++) {
            BlockPos prev = points.get(i - 1);
            BlockPos cur = points.get(i);
            total += Math.sqrt(
                    Math.pow(cur.getX() - prev.getX(), 2) +
                    Math.pow(cur.getY() - prev.getY(), 2) +
                    Math.pow(cur.getZ() - prev.getZ(), 2)
            );
        }
        return total;
    }
}
