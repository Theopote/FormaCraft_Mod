package com.formacraft.common.cluster;

import com.formacraft.common.cluster.zoning.BuildingProgram;
import com.formacraft.common.semantic.ComponentPreset;
import net.minecraft.util.math.BlockPos;

/**
 * ZonedSlot（带功能分区的槽位）
 * <p>
 * K3.1 核心：扩展 BuildingSlot，包含 preset 信息
 * <p>
 * 用于将 BuildingSlot 转换为包含组件预设信息的 ZonedSlot
 */
public record ZonedSlot(
        /** 建筑锚点（世界坐标） */
        BlockPos anchor,
        
        /** 路径进度 [0..1] */
        float t,
        
        /** 街道侧 */
        StreetSide side,
        
        /** Lane 索引 */
        int laneIndex,
        
        /** 朝向路径的方向 */
        PathClusterLayout.Facing facing,
        
        /** 建筑宽度 */
        int width,
        
        /** 建筑深度 */
        int depth,
        
        /** 高度提示 */
        int heightHint,
        
        /** 建筑功能 */
        BuildingProgram program,
        
        /** 预设 ID */
        String presetId,
        
        /** 预设文本（用于 Prompt） */
        String presetText
) {
    /**
     * 从 BuildingSlot 和 ComponentPreset 创建 ZonedSlot
     */
    public static ZonedSlot from(PathClusterLayout.BuildingSlot slot, ComponentPreset preset) {
        if (slot == null || preset == null) {
            throw new IllegalArgumentException("slot and preset must not be null");
        }
        
        return new ZonedSlot(
                slot.anchor,
                slot.t,
                slot.side,
                slot.laneIndex,
                slot.facing,
                slot.width,
                slot.depth,
                slot.heightHint,
                slot.program,
                preset.id,
                preset.toPromptText()
        );
    }
}

