package com.formacraft.client.tool;

import com.formacraft.common.cluster.zoning.ISemanticLabelQuery;
import com.formacraft.common.skeleton.PathSkeleton;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SemanticLabelQueryAdapter（语义标签查询适配器）
 * <p>
 * 将 SemanticLabelTool 适配为 ISemanticLabelQuery 接口
 * <p>
 * K3 核心：根据路径进度查询标签
 */
public final class SemanticLabelQueryAdapter implements ISemanticLabelQuery {

    private final PathSkeleton pathSkeleton;
    private final SemanticLabelTool labelTool;

    public SemanticLabelQueryAdapter(PathSkeleton pathSkeleton, SemanticLabelTool labelTool) {
        this.pathSkeleton = pathSkeleton;
        this.labelTool = labelTool;
    }

    @Override
    public Set<String> queryLabelsNearPathT(float t) {
        if (pathSkeleton == null || !pathSkeleton.isValid() || labelTool == null) {
            return new HashSet<>();
        }

        // 根据路径进度 t 计算对应的世界坐标
        List<BlockPos> nodes = pathSkeleton.nodes;
        if (nodes == null || nodes.isEmpty()) {
            return new HashSet<>();
        }

        // 线性插值找到对应的节点位置
        int index = (int) (t * (nodes.size() - 1));
        index = Math.max(0, Math.min(nodes.size() - 1, index));
        BlockPos pos = nodes.get(index);

        // 查询该位置附近的标签（简化：使用固定半径）
        int radius = 5; // 查询半径（格）
        return queryLabelsNearPos(pos, radius);
    }

    /**
     * 查询指定位置附近的标签
     */
    private Set<String> queryLabelsNearPos(BlockPos pos, int radius) {
        Set<String> labels = new HashSet<>();
        
        if (labelTool == null) {
            return labels;
        }

        // 获取所有标签
        List<SemanticLabelTool.AreaLabel> allLabels = labelTool.getLabels();
        if (allLabels == null || allLabels.isEmpty()) {
            return labels;
        }

        // 检查每个标签是否在范围内
        for (SemanticLabelTool.AreaLabel label : allLabels) {
            if (label == null || label.outline() == null || label.outline().isEmpty()) {
                continue;
            }

            // 计算标签中心点
            double cx = 0, cz = 0;
            for (BlockPos p : label.outline()) {
                cx += p.getX() + 0.5;
                cz += p.getZ() + 0.5;
            }
            cx /= label.outline().size();
            cz /= label.outline().size();

            // 计算距离（使用标签的 range 或固定 radius）
            int effectiveRadius = label.range() > 0 ? label.range() : radius;
            double dx = (pos.getX() + 0.5) - cx;
            double dz = (pos.getZ() + 0.5) - cz;
            double dist = Math.sqrt(dx * dx + dz * dz);

            if (dist <= effectiveRadius) {
                labels.add(label.name());
            }
        }

        return labels;
    }
}

