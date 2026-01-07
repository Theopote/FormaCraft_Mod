package com.formacraft.server.skeleton.gen.path;

import com.formacraft.common.cluster.PathClusterLayout;
import com.formacraft.common.cluster.ZonedSlot;
import com.formacraft.common.cluster.zoning.ProgramPresetResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * PathClusterLayoutToZonedSlots（路径建筑群布局 → ZonedSlot 转换器）
 * 
 * K3.1 核心：将 PathClusterLayout 转换为 ZonedSlot 列表
 * 
 * 关键功能：
 * - 为每个 BuildingSlot 解析 ComponentPreset
 * - 创建 ZonedSlot（包含 preset 信息）
 * - 支持风格偏置
 */
public final class PathClusterLayoutToZonedSlots {

    private PathClusterLayoutToZonedSlots() {}

    /**
     * 将 PathClusterLayout 转换为 ZonedSlot 列表
     * 
     * @param layout 路径建筑群布局
     * @param styleId 风格 ID（可选，用于风格偏置）
     * @return ZonedSlot 列表
     */
    public static List<ZonedSlot> toZonedSlots(
            PathClusterLayout layout,
            String styleId
    ) {
        List<ZonedSlot> zoned = new ArrayList<>();
        
        if (layout == null || !layout.isValid()) {
            return zoned;
        }

        for (PathClusterLayout.BuildingSlot slot : layout.slots) {
            // 解析 preset
            var preset = ProgramPresetResolver.resolve(styleId, slot.program);
            
            // 创建 ZonedSlot
            zoned.add(ZonedSlot.from(slot, preset));
        }

        return zoned;
    }
}

