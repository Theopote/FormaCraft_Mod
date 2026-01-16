package com.formacraft.common.component.socket.continuous;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * ContinuousPlacementEngine（连续放置引擎）：H4 的核心类。
 * <p>
 * 完整流程：
 * ContinuousSocket
 *   ↓ sample
 * [ P0, P1, P2, ... ]
 *   ↓ segment grouping
 * [ S0 ][ S1 ][ S2 ]
 *   ↓ per-segment anchor
 * [ Anchor0, Anchor1, Anchor2 ]
 *   ↓ component expansion
 * BlockPatch[]
 */
public final class ContinuousPlacementEngine {
    private ContinuousPlacementEngine() {}

    /**
     * 沿连续插槽放置构件
     * 
     * @param socket 连续插槽
     * @param component 构件定义
     * @param policy 放置策略
     * @return BlockPatch 列表
     */
    public static List<BlockPatch> place(
            ContinuousSocket socket,
            ComponentDefinition component,
            ContinuousPlacementPolicy policy
    ) {
        List<BlockPatch> patches = new ArrayList<>();

        if (socket == null || component == null || policy == null) {
            return patches;
        }

        // 1. 采样点
        List<BlockPos> samples = socket.samplePoints(1);
        if (samples.size() < 2) {
            return patches;
        }

        // 2. 切分为段
        List<Segment> segments = Segmenter.split(samples, policy.segmentLength());
        if (segments.isEmpty()) {
            return patches;
        }

        // 3. 对每个段进行处理
        for (int i = 0; i < segments.size(); i++) {
            Segment seg = segments.get(i);

            // 检查是否是转角
            if (CornerHandler.isCorner(seg, i, segments)) {
                // 转角处理
                patches.addAll(
                        CornerHandler.handle(seg, component, policy)
                );
            } else {
                // 普通段：构件展开
                patches.addAll(
                        ComponentExpander.expand(component, seg, policy)
                );
            }
        }

        return patches;
    }
}
