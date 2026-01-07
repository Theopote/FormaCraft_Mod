package com.formacraft.server.skeleton.gen.path;

import com.formacraft.common.cluster.PathClusterLayout;
import com.formacraft.common.skeleton.PathSkeleton;
import com.formacraft.server.skeleton.gen.GenerationContext;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * PathClusterLayoutGenerator（路径建筑群布局生成器）
 * 
 * K1 基础版本：单排建筑
 * 
 * 从 PathSkeleton 生成 PathClusterLayout
 * 
 * 核心算法：
 * 1. 沿路径采样（Path Sampling）
 * 2. 计算左右偏移（生成街道两侧）
 * 3. 生成 BuildingSlot（顺地形）
 * 
 * 注意：K2 多排布局请使用 PathStreetLayoutBuilder
 */
public final class PathClusterLayoutGenerator {

    private PathClusterLayoutGenerator() {}

    /**
     * 从 PathSkeleton 生成 PathClusterLayout
     * 
     * @param pathSkeleton 路径骨架
     * @param ctx 生成上下文（用于地形采样）
     * @param spacing 建筑间距（默认 8 格）
     * @param offsetFromPath 建筑距离路径的偏移（默认 5 格）
     * @param buildingWidth 建筑宽度（默认 6 格）
     * @param buildingDepth 建筑深度（默认 6 格）
     * @param heightHint 高度提示（默认 8 格）
     * @return 路径建筑群布局
     */
    public static PathClusterLayout generate(
            PathSkeleton pathSkeleton,
            GenerationContext ctx,
            int spacing,
            int offsetFromPath,
            int buildingWidth,
            int buildingDepth,
            int heightHint
    ) {
        if (pathSkeleton == null || !pathSkeleton.isValid() || ctx == null) {
            return new PathClusterLayout(List.of());
        }

        List<BlockPos> nodes = pathSkeleton.nodes;
        if (nodes == null || nodes.size() < 2) {
            return new PathClusterLayout(List.of());
        }

        // 1. 沿路径采样（Path Sampling）
        List<BlockPos> anchors = samplePathAnchors(nodes, spacing);

        // 2. 生成 BuildingSlot（左右两侧）
        List<PathClusterLayout.BuildingSlot> slots = new ArrayList<>();

        for (int i = 0; i < anchors.size(); i++) {
            BlockPos p = anchors.get(i);
            BlockPos prev = (i > 0) ? anchors.get(i - 1) : nodes.get(0);

            // 左侧建筑
            BlockPos leftAnchor = offsetFromPath(p, prev, offsetFromPath, true);
            leftAnchor = adaptToTerrain(ctx, leftAnchor);
            slots.add(new PathClusterLayout.BuildingSlot(
                leftAnchor,
                PathClusterLayout.Facing.LEFT_OF_PATH,
                buildingWidth,
                buildingDepth,
                heightHint
            ));

            // 右侧建筑
            BlockPos rightAnchor = offsetFromPath(p, prev, offsetFromPath, false);
            rightAnchor = adaptToTerrain(ctx, rightAnchor);
            slots.add(new PathClusterLayout.BuildingSlot(
                rightAnchor,
                PathClusterLayout.Facing.RIGHT_OF_PATH,
                buildingWidth,
                buildingDepth,
                heightHint
            ));
        }

        return new PathClusterLayout(slots);
    }

    /**
     * 沿路径采样锚点
     * 
     * 算法：累积距离，每 spacing 格放置一个锚点
     */
    private static List<BlockPos> samplePathAnchors(List<BlockPos> nodes, int spacing) {
        List<BlockPos> anchors = new ArrayList<>();
        if (nodes == null || nodes.size() < 2) {
            return anchors;
        }

        // 第一个节点总是锚点
        anchors.add(nodes.get(0));

        int acc = 0;
        for (int i = 1; i < nodes.size(); i++) {
            BlockPos prev = nodes.get(i - 1);
            BlockPos curr = nodes.get(i);
            
            // 累积曼哈顿距离
            acc += Math.abs(curr.getX() - prev.getX()) + 
                   Math.abs(curr.getY() - prev.getY()) + 
                   Math.abs(curr.getZ() - prev.getZ());

            if (acc >= spacing) {
                anchors.add(curr);
                acc = 0;
            }
        }

        // 确保最后一个节点也是锚点
        if (!anchors.contains(nodes.get(nodes.size() - 1))) {
            anchors.add(nodes.get(nodes.size() - 1));
        }

        return anchors;
    }

    /**
     * 计算左右偏移（生成街道两侧）
     * 
     * @param p 当前点
     * @param prev 前一个点（用于计算方向）
     * @param offset 偏移距离
     * @param left true=左侧，false=右侧
     * @return 偏移后的位置
     */
    private static BlockPos offsetFromPath(
            BlockPos p,
            BlockPos prev,
            int offset,
            boolean left
    ) {
        int dx = p.getX() - prev.getX();
        int dz = p.getZ() - prev.getZ();

        // 垂直方向（左法向 / 右法向）
        // 左法向：(-dz, dx)
        // 右法向：(dz, -dx)
        int nx = left ? -dz : dz;
        int nz = left ? dx : -dx;

        // 单位化（简化版：只取符号）
        if (nx != 0) nx = Integer.signum(nx);
        if (nz != 0) nz = Integer.signum(nz);

        // 如果方向为零（垂直或水平），使用默认方向
        if (nx == 0 && nz == 0) {
            // 默认：左侧为西，右侧为东
            nx = left ? -1 : 1;
            nz = 0;
        }

        return p.add(nx * offset, 0, nz * offset);
    }

    /**
     * 地形自适应（复用前面设计的思想）
     * 
     * 将锚点调整到地表高度
     */
    private static BlockPos adaptToTerrain(GenerationContext ctx, BlockPos p) {
        int groundY = ctx.getSurfaceY(p.getX(), p.getZ());
        return new BlockPos(p.getX(), groundY, p.getZ());
    }
}

