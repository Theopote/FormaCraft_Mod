package com.formacraft.common.cluster;

import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * PathClusterLayout（路径建筑群布局）
 * 
 * 这是 K1 的核心：从"单体建筑生成"跃迁到"城市级生成"
 * 
 * 本质：一维主骨架（Path） + 二维附着（Buildings）
 * 
 * PathSkeleton：告诉你"主干在哪里"
 * PathClusterLayout：告诉你"建筑站位在哪里"
 */
public final class PathClusterLayout {

    public final List<BuildingSlot> slots;

    public PathClusterLayout(List<BuildingSlot> slots) {
        this.slots = slots != null ? List.copyOf(slots) : List.of();
    }

    /**
     * 建筑槽位（Building Slot）
     * 
     * 定义沿路径的一个建筑位置和参数
     */
    public static final class BuildingSlot {
        /** 建筑锚点（世界坐标） */
        public final BlockPos anchor;
        
        /** 朝向路径的方向 */
        public final Facing facing;
        
        /** 建筑宽度（横向，垂直于路径） */
        public final int width;
        
        /** 建筑深度（纵向，沿路径方向） */
        public final int depth;
        
        /** 高度提示（用于 AI 生成） */
        public final int heightHint;

        public BuildingSlot(
                BlockPos anchor,
                Facing facing,
                int width,
                int depth,
                int heightHint
        ) {
            this.anchor = anchor != null ? anchor : BlockPos.ORIGIN;
            this.facing = facing != null ? facing : Facing.ALONG_PATH;
            this.width = Math.max(1, width);
            this.depth = Math.max(1, depth);
            this.heightHint = Math.max(1, heightHint);
        }
    }

    /**
     * 建筑朝向路径的方向
     */
    public enum Facing {
        /** 路径左侧 */
        LEFT_OF_PATH,
        /** 路径右侧 */
        RIGHT_OF_PATH,
        /** 沿路径方向（道路本身） */
        ALONG_PATH
    }

    /**
     * 检查是否有效（至少有一个槽位）
     */
    public boolean isValid() {
        return slots != null && !slots.isEmpty();
    }

    /**
     * 获取槽位数量
     */
    public int getSlotCount() {
        return slots != null ? slots.size() : 0;
    }
}

