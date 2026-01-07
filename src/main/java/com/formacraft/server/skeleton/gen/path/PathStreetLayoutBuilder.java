package com.formacraft.server.skeleton.gen.path;

import com.formacraft.common.cluster.PathClusterLayout;
import com.formacraft.common.cluster.StreetProfile;
import com.formacraft.common.cluster.StreetSide;
import com.formacraft.common.cluster.zoning.BuildingProgram;
import com.formacraft.common.cluster.zoning.ISemanticLabelQuery;
import com.formacraft.common.cluster.zoning.PathZoningPlanner;
import com.formacraft.common.cluster.zoning.ZoningProfile;
import com.formacraft.common.skeleton.PathSkeleton;
import com.formacraft.server.skeleton.gen.GenerationContext;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * PathStreetLayoutBuilder（路径街道布局构建器）
 * <p>
 * K2 核心：在 K1 基础上扩展为多排街道/放射街区/城墙走廊
 * <p>
 * 核心算法：
 * 1. 沿路径采样锚点
 * 2. 多排偏移公式：offset = roadWidth / 2 + laneSpacing * (lane + 1)
 * 3. 左右两侧 + 多排布局
 * 4. 地形自适应
 */
public final class PathStreetLayoutBuilder {

    private PathStreetLayoutBuilder() {}

    /**
     * 从 PathSkeleton 和 StreetProfile 生成 PathClusterLayout（K2 版本）
     * 
     * @param pathSkeleton 路径骨架
     * @param profile 街道剖面
     * @param ctx 生成上下文（用于地形采样）
     * @param spacing 建筑间距（默认 8 格）
     * @param buildingWidth 建筑宽度（默认 6 格）
     * @param buildingDepth 建筑深度（默认 6 格）
     * @param heightHint 高度提示（默认 8 格）
     * @return 路径建筑群布局
     */
    public static PathClusterLayout build(
            PathSkeleton pathSkeleton,
            StreetProfile profile,
            GenerationContext ctx,
            int spacing,
            int buildingWidth,
            int buildingDepth,
            int heightHint
    ) {
        return build(pathSkeleton, profile, null, null, ctx, spacing, buildingWidth, buildingDepth, heightHint);
    }

    /**
     * 从 PathSkeleton 和 StreetProfile 生成 PathClusterLayout（K3 版本，支持功能分区）
     * 
     * @param pathSkeleton 路径骨架
     * @param profile 街道剖面
     * @param zoningProfile 分区预设（K3 新增，可为 null）
     * @param labelQuery 标签查询接口（K3 新增，可为 null）
     * @param ctx 生成上下文（用于地形采样）
     * @param spacing 建筑间距（默认 8 格）
     * @param buildingWidth 建筑宽度（默认 6 格）
     * @param buildingDepth 建筑深度（默认 6 格）
     * @param heightHint 高度提示（默认 8 格）
     * @return 路径建筑群布局
     */
    public static PathClusterLayout build(
            PathSkeleton pathSkeleton,
            StreetProfile profile,
            ZoningProfile zoningProfile,
            ISemanticLabelQuery labelQuery,
            GenerationContext ctx,
            int spacing,
            int buildingWidth,
            int buildingDepth,
            int heightHint
    ) {
        if (pathSkeleton == null || !pathSkeleton.isValid() || profile == null || ctx == null) {
            return new PathClusterLayout(List.of());
        }

        List<BlockPos> nodes = pathSkeleton.nodes;
        if (nodes == null || nodes.size() < 2) {
            return new PathClusterLayout(List.of());
        }

        List<PathClusterLayout.BuildingSlot> slots = new ArrayList<>();

        // 1. 沿路径采样锚点
        List<BlockPos> anchors = samplePathAnchors(nodes, spacing);

        // 2. 初始化分区规划器（K3）
        PathZoningPlanner zoning = null;
        if (zoningProfile != null) {
            zoning = new PathZoningPlanner(zoningProfile);
        }

        // 3. 为每个锚点生成多排建筑（K3：支持功能分区）
        for (int i = 0; i < anchors.size(); i++) {
            BlockPos p = anchors.get(i);
            BlockPos prev = (i > 0) ? anchors.get(i - 1) : nodes.getFirst();

            // 计算路径进度 t [0..1]（K3）
            float t = (anchors.size() <= 1) ? 0.0f : (i / (float) (anchors.size() - 1));

            // 查询该位置的标签（K3）
            Set<String> labels = (labelQuery != null) 
                    ? labelQuery.queryLabelsNearPathT(t)
                    : PathZoningPlanner.emptyLabels();

            // 左右两侧
            for (StreetSide side : StreetSide.values()) {
                // 如果不对称且是右侧，跳过（只生成左侧）
                if (!profile.symmetric() && side == StreetSide.RIGHT) {
                    continue;
                }

                // 多排建筑
                for (int lane = 0; lane < profile.laneCount(); lane++) {
                    // 多排偏移公式
                    int offset = profile.roadWidth() / 2
                               + profile.laneSpacing() * (lane + 1);

                    BlockPos anchor = offsetFromPath(
                            p, prev, offset,
                            side == StreetSide.LEFT
                    );

                    // 地形自适应
                    anchor = adaptToTerrain(ctx, anchor);

                    // 解析建筑功能（K3）
                    BuildingProgram program = (zoning != null)
                            ? zoning.resolve(t, side, lane, labels)
                            : BuildingProgram.RESIDENTIAL;

                    slots.add(new PathClusterLayout.BuildingSlot(
                            anchor,
                            t,
                            side,
                            lane,
                            side == StreetSide.LEFT
                                    ? PathClusterLayout.Facing.LEFT_OF_PATH
                                    : PathClusterLayout.Facing.RIGHT_OF_PATH,
                            buildingWidth,
                            buildingDepth,
                            heightHint,
                            program
                    ));
                }
            }
        }

        return new PathClusterLayout(slots);
    }

    /**
     * 沿路径采样锚点
     */
    private static List<BlockPos> samplePathAnchors(List<BlockPos> nodes, int spacing) {
        List<BlockPos> anchors = new ArrayList<>();
        if (nodes == null || nodes.size() < 2) {
            return anchors;
        }

        anchors.add(nodes.getFirst());

        int acc = 0;
        for (int i = 1; i < nodes.size(); i++) {
            BlockPos prev = nodes.get(i - 1);
            BlockPos curr = nodes.get(i);
            
            acc += Math.abs(curr.getX() - prev.getX()) + 
                   Math.abs(curr.getY() - prev.getY()) + 
                   Math.abs(curr.getZ() - prev.getZ());

            if (acc >= spacing) {
                anchors.add(curr);
                acc = 0;
            }
        }

        if (!anchors.contains(nodes.getLast())) {
            anchors.add(nodes.getLast());
        }

        return anchors;
    }

    /**
     * 计算左右偏移（生成街道两侧）
     */
    private static BlockPos offsetFromPath(
            BlockPos p,
            BlockPos prev,
            int offset,
            boolean left
    ) {
        int dx = p.getX() - prev.getX();
        int dz = p.getZ() - prev.getZ();

        int nx = left ? -dz : dz;
        int nz = left ? dx : -dx;

        if (nx != 0) nx = Integer.signum(nx);
        if (nz != 0) nz = Integer.signum(nz);

        if (nx == 0 && nz == 0) {
            nx = left ? -1 : 1;
            nz = 0;
        }

        return p.add(nx * offset, 0, nz * offset);
    }

    /**
     * 地形自适应
     */
    private static BlockPos adaptToTerrain(GenerationContext ctx, BlockPos p) {
        int groundY = ctx.getSurfaceY(p.getX(), p.getZ());
        return new BlockPos(p.getX(), groundY, p.getZ());
    }
}

