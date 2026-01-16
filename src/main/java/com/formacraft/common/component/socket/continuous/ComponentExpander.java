package com.formacraft.common.component.socket.continuous;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.server.build.BlockPatch;

import java.util.ArrayList;
import java.util.List;

/**
 * ComponentExpander（构件展开器）：将构件展开为 BlockPatch 列表。
 */
public final class ComponentExpander {
    private ComponentExpander() {}

    /**
     * 展开构件
     * 
     * @param component 构件定义
     * @param segment 段
     * @param policy 放置策略
     * @return BlockPatch 列表
     */
    public static List<BlockPatch> expand(
            ComponentDefinition component,
            Segment segment,
            ContinuousPlacementPolicy policy
    ) {
        List<BlockPatch> patches = new ArrayList<>();

        if (component == null || segment == null || segment.points().isEmpty()) {
            return patches;
        }

        // v1 简化实现：在段的中心点放置构件
        BlockPos anchor = AnchorResolver.resolve(segment, policy);
        Direction facing = FacingResolver.fromSegment(segment, null);

        // 根据高度策略调整 Y 坐标
        int y = anchor.getY();
        switch (policy.heightPolicy()) {
            case FOLLOW_TERRAIN -> {
                // 贴地：使用段的最低点
                y = segment.points().stream()
                        .mapToInt(BlockPos::getY)
                        .min()
                        .orElse(anchor.getY());
            }
            case STEP_TERRACE -> {
                // 台阶：使用段的平均高度
                y = (int) segment.points().stream()
                        .mapToInt(BlockPos::getY)
                        .average()
                        .orElse(anchor.getY());
            }
            case FIXED_BASE -> {
                // 固定高度：使用锚点的 Y
                y = anchor.getY();
            }
            case ADAPTIVE_FOUNDATION -> {
                // 自适应底座：使用段的最低点（v1 简化）
                y = segment.points().stream()
                        .mapToInt(BlockPos::getY)
                        .min()
                        .orElse(anchor.getY());
            }
        }

        BlockPos finalAnchor = new BlockPos(anchor.getX(), y, anchor.getZ());

        // 生成 BlockPatch（v1 简化：直接使用构件的所有方块）
        if (component.blocks != null) {
            for (ComponentDefinition.BlockEntry be : component.blocks) {
                if (be == null) continue;

                // 计算世界坐标（简化：直接偏移）
                int wx = finalAnchor.getX() + be.dx;
                int wy = finalAnchor.getY() + be.dy;
                int wz = finalAnchor.getZ() + be.dz;

                patches.add(new BlockPatch(
                        BlockPatch.PLACE,
                        wx, wy, wz,
                        be.block != null ? be.block : "minecraft:air"
                ));
            }
        }

        return patches;
    }
}
