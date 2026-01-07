package com.formacraft.server.skeleton.gen.path;

import com.formacraft.common.cluster.PathClusterLayout;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.model.build.Footprint;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * PathClusterLayoutToBuildingSpecs（路径建筑群布局 → BuildingSpec 转换器）
 * 
 * 将 PathClusterLayout 转换为多个 BuildingSpec
 * 
 * 关键设计原则：
 * - ❌ AI 不决定"建筑站哪"（站位由算法决定）
 * - ✅ AI 决定"建筑长什么样"（风格、细节由 AI 决定）
 */
public final class PathClusterLayoutToBuildingSpecs {

    private PathClusterLayoutToBuildingSpecs() {}

    /**
     * 将 PathClusterLayout 转换为 BuildingSpec 列表
     * 
     * @param layout 路径建筑群布局
     * @param defaultStyle 默认风格（可选）
     * @return BuildingSpec 列表
     */
    public static List<BuildingSpec> toBuildingSpecs(
            PathClusterLayout layout,
            BuildingStyle defaultStyle
    ) {
        List<BuildingSpec> specs = new ArrayList<>();
        
        if (layout == null || !layout.isValid()) {
            return specs;
        }

        for (PathClusterLayout.BuildingSlot slot : layout.slots) {
            BuildingSpec spec = new BuildingSpec();
            
            // 基础信息
            spec.setType(BuildingType.HOUSE); // 默认类型，AI 可以覆盖
            spec.setStyle(defaultStyle != null ? defaultStyle : BuildingStyle.MEDIEVAL);
            
            // 位置和尺寸（通过 Footprint）
            Footprint footprint = new Footprint(slot.width, slot.depth);
            spec.setFootprint(footprint);
            
            // 高度
            spec.setHeight(slot.heightHint);
            
            // 其他参数（AI 可以补充）
            spec.setNotes("Building along path");
            
            // 将朝向信息存储在 extra 中（供生成器使用）
            if (spec.getExtra() == null) {
                spec.setExtra(new java.util.HashMap<>());
            }
            spec.getExtra().put("facing", resolveFacing(slot.facing).name());
            spec.getExtra().put("anchor", slot.anchor);
            
            specs.add(spec);
        }

        return specs;
    }

    /**
     * 解析建筑朝向
     * 
     * @param facing 相对于路径的朝向
     * @return Minecraft Direction
     */
    private static Direction resolveFacing(PathClusterLayout.Facing facing) {
        // 简化实现：
        // - LEFT_OF_PATH: 朝向路径（东）
        // - RIGHT_OF_PATH: 朝向路径（西）
        // - ALONG_PATH: 沿路径方向（南）
        
        return switch (facing) {
            case LEFT_OF_PATH -> Direction.EAST;  // 左侧建筑朝向路径（东）
            case RIGHT_OF_PATH -> Direction.WEST; // 右侧建筑朝向路径（西）
            case ALONG_PATH -> Direction.SOUTH;   // 沿路径方向（南）
        };
    }
}

