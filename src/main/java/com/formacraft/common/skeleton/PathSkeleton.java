package com.formacraft.common.skeleton;

import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * A path-based topology skeleton.
 * 
 * PathTool 的真实语义：一条"空间主干（spine）"
 * 
 * 它天然适合生成：
 * - 道路 → PATH_POLYLINE (ROAD)
 * - 长城 → PATH_POLYLINE + WALL_MODULE (WALL)
 * - 桥 → SPAN_SUSPENSION / LINEAR_PATH (BRIDGE)
 * - 沿街建筑 → PATH_POLYLINE + GRID_ATTACH (ALONG_PATH_BUILDING)
 * - 山路/台阶 → PATH_POLYLINE + TERRACE (GENERIC)
 * 
 * This is generated from PathTool and consumed by generators.
 */
public final class PathSkeleton {

    public final List<BlockPos> nodes;   // world positions
    public final int corridorRadius;     // build influence radius
    public final boolean snapToGround;

    /** semantic intent from tools / prompt */
    public final PathIntent intent;

    /**
     * Path 语义意图
     */
    public enum PathIntent {
        /** 道路 */
        ROAD,
        /** 城墙/长城 */
        WALL,
        /** 桥梁 */
        BRIDGE,
        /** 沿路径建筑 */
        ALONG_PATH_BUILDING,
        /** 通用路径 */
        GENERIC
    }

    public PathSkeleton(
            List<BlockPos> nodes,
            int corridorRadius,
            boolean snapToGround,
            PathIntent intent
    ) {
        this.nodes = nodes != null ? List.copyOf(nodes) : List.of();
        this.corridorRadius = Math.max(1, corridorRadius);
        this.snapToGround = snapToGround;
        this.intent = intent != null ? intent : PathIntent.GENERIC;
    }

    /**
     * 检查是否有效（至少需要 2 个节点）
     */
    public boolean isValid() {
        return nodes != null && nodes.size() >= 2;
    }

    /**
     * 获取路径长度（节点数）
     */
    public int getNodeCount() {
        return nodes != null ? nodes.size() : 0;
    }
}

