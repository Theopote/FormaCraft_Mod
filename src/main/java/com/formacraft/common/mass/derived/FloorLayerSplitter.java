package com.formacraft.common.mass.derived;

import com.formacraft.common.mass.HeightRange;

import java.util.ArrayList;
import java.util.List;

/**
 * FloorLayerSplitter（楼层切分器）
 * <p>
 * 从 HeightRange 切分出 FloorLayer 列表
 * <p>
 * 核心思想：
 * 只需要一个层高策略，就可以将 HeightRange 切分为多个 FloorLayer
 */
public final class FloorLayerSplitter {

    private FloorLayerSplitter() {}

    /**
     * 从 HeightRange 切分出 FloorLayer 列表
     * <p>
     * 示例：
     * baseY = 64, topY = 76, floorHeight = 4
     * → 3 层：
     * - Layer 0: 64–67 (GROUND)
     * - Layer 1: 68–71 (STANDARD)
     * - Layer 2: 72–76 (TOP)
     *
     * @param heightRange 高度范围
     * @param floorHeight 层高（block）
     * @return FloorLayer 列表
     */
    public static List<FloorLayer> splitHeightRange(HeightRange heightRange, int floorHeight) {
        if (heightRange == null || floorHeight <= 0) {
            return List.of();
        }

        List<FloorLayer> layers = new ArrayList<>();
        int baseY = heightRange.baseY;
        int topY = heightRange.topY;
        int currentY = baseY;
        int layerIndex = 0;

        while (currentY <= topY) {
            // 计算这一层的顶部
            int layerTopY = Math.min(currentY + floorHeight - 1, topY);

            // 确定角色
            FloorLayer.FloorRole role;
            if (layerIndex == 0) {
                role = FloorLayer.FloorRole.GROUND; // 首层
            } else if (layerTopY >= topY) {
                role = FloorLayer.FloorRole.TOP; // 顶层
            } else {
                role = FloorLayer.FloorRole.STANDARD; // 标准层
            }

            // 创建 FloorLayer
            layers.add(new FloorLayer(layerIndex, currentY, layerTopY, role));

            // 移动到下一层
            currentY = layerTopY + 1;
            layerIndex++;
        }

        return layers;
    }

    /**
     * 从 BuildingMass 的高度范围切分出 FloorLayer 列表
     * <p>
     * 使用默认层高（4 block）
     *
     * @param heightRange 高度范围
     * @return FloorLayer 列表
     */
    public static List<FloorLayer> splitHeightRange(HeightRange heightRange) {
        return splitHeightRange(heightRange, 4); // 默认层高 4 block
    }
}
