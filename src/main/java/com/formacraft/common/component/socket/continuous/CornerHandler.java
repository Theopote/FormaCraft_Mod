package com.formacraft.common.component.socket.continuous;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.server.build.BlockPatch;

import java.util.ArrayList;
import java.util.List;

/**
 * CornerHandler（转角处理器）：处理转角处的构件放置。
 */
public final class CornerHandler {
    private CornerHandler() {}

    /**
     * 处理转角
     * 
     * @param segment 转角段
     * @param component 构件定义
     * @param policy 放置策略
     * @return BlockPatch 列表
     */
    public static List<BlockPatch> handle(
            Segment segment,
            ComponentDefinition component,
            ContinuousPlacementPolicy policy
    ) {
        List<BlockPatch> patches = new ArrayList<>();

        // v1 简化实现：根据 cornerMode 选择处理方式
        switch (policy.cornerMode()) {
            case CUT -> {
                // 切断（直角）：直接放置构件，不做特殊处理
                patches.addAll(ComponentExpander.expand(component, segment, policy));
            }
            case MITER -> {
                // 45° 斜接：放置斜接构件（v1：暂时用普通构件）
                patches.addAll(ComponentExpander.expand(component, segment, policy));
            }
            case PILLAR -> {
                // 转角插柱：在转角处放置柱子/角楼
                // v1：暂时用普通构件，后续可以查询专门的转角构件
                patches.addAll(ComponentExpander.expand(component, segment, policy));
            }
            case SMOOTH -> {
                // 平滑过渡：放置平滑过渡构件（v1：暂时用普通构件）
                patches.addAll(ComponentExpander.expand(component, segment, policy));
            }
        }

        return patches;
    }

    /**
     * 检查是否是转角
     * 
     * @param segment 当前段
     * @param index 段索引
     * @param segments 所有段
     * @return true 如果是转角
     */
    public static boolean isCorner(Segment segment, int index, List<Segment> segments) {
        if (segments.size() < 2 || index < 0 || index >= segments.size()) {
            return false;
        }

        // v1 简化：检查相邻段的方向变化
        if (index > 0) {
            Segment prev = segments.get(index - 1);
            Vec3d prevDir = prev.direction();
            Vec3d curDir = segment.direction();

            // 计算方向夹角（点积）
            double dot = prevDir.dotProduct(curDir);
            double angle = Math.acos(Math.max(-1.0, Math.min(1.0, dot)));

            // 如果角度变化超过 30°，认为是转角
            if (angle > Math.PI / 6) {
                return true;
            }
        }

        return false;
    }
}
