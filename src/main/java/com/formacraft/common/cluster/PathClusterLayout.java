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
     * 
     * K3 扩展：添加了路径进度、侧、lane、功能等字段
     */
    public static final class BuildingSlot {
        /** 建筑锚点（世界坐标） */
        public final BlockPos anchor;
        
        /** 路径进度 [0..1]（K3 新增） */
        public final float t;
        
        /** 街道侧（K3 新增） */
        public final com.formacraft.common.cluster.StreetSide side;
        
        /** Lane 索引（K3 新增） */
        public final int laneIndex;
        
        /** 朝向路径的方向 */
        public final Facing facing;
        
        /** 建筑宽度（横向，垂直于路径） */
        public final int width;
        
        /** 建筑深度（纵向，沿路径方向） */
        public final int depth;
        
        /** 高度提示（用于 AI 生成） */
        public final int heightHint;
        
        /** 建筑功能（K3 新增） */
        public final com.formacraft.common.cluster.zoning.BuildingProgram program;

        /**
         * K1/K2 兼容构造函数（无 zoning 信息）
         */
        public BuildingSlot(
                BlockPos anchor,
                Facing facing,
                int width,
                int depth,
                int heightHint
        ) {
            this(anchor, 0.0f, com.formacraft.common.cluster.StreetSide.LEFT, 0,
                 facing, width, depth, heightHint,
                 com.formacraft.common.cluster.zoning.BuildingProgram.RESIDENTIAL);
        }

        /**
         * K3 完整构造函数（包含 zoning 信息）
         */
        public BuildingSlot(
                BlockPos anchor,
                float t,
                com.formacraft.common.cluster.StreetSide side,
                int laneIndex,
                Facing facing,
                int width,
                int depth,
                int heightHint,
                com.formacraft.common.cluster.zoning.BuildingProgram program
        ) {
            this.anchor = anchor != null ? anchor : BlockPos.ORIGIN;
            this.t = Math.max(0.0f, Math.min(1.0f, t));
            this.side = side != null ? side : com.formacraft.common.cluster.StreetSide.LEFT;
            this.laneIndex = Math.max(0, laneIndex);
            this.facing = facing != null ? facing : Facing.ALONG_PATH;
            this.width = Math.max(1, width);
            this.depth = Math.max(1, depth);
            this.heightHint = Math.max(1, heightHint);
            this.program = program != null ? program : com.formacraft.common.cluster.zoning.BuildingProgram.RESIDENTIAL;
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

