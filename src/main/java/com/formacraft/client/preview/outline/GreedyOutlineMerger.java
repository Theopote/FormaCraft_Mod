package com.formacraft.client.preview.outline;

import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 2D Greedy 合并外露面：
 * - 对每个 direction、每个 plane(d) 单独做二维矩形合并
 * - 单元粒度为 1x1 block face
 */
public final class GreedyOutlineMerger {
    private GreedyOutlineMerger() {}

    /**
     * @param facesByDirPlane PatchOutlineBuilder.collectFaces(...) 的结果
     */
    public static List<OutlineQuad> merge(Map<Direction, Map<Integer, Set<Long>>> facesByDirPlane) {
        if (facesByDirPlane == null || facesByDirPlane.isEmpty()) return List.of();

        List<OutlineQuad> result = new ArrayList<>();
        for (Direction dir : Direction.values()) {
            Map<Integer, Set<Long>> byPlane = facesByDirPlane.get(dir);
            if (byPlane == null || byPlane.isEmpty()) continue;
            for (Map.Entry<Integer, Set<Long>> e : byPlane.entrySet()) {
                int d = e.getKey();
                Set<Long> cells = e.getValue();
                if (cells == null || cells.isEmpty()) continue;
                result.addAll(mergePlane(dir, d, cells));
            }
        }
        return result;
    }

    private static List<OutlineQuad> mergePlane(Direction dir, int d, Set<Long> cells) {
        // 用 remaining 做移除，避免修改原集合
        Set<Long> remaining = new HashSet<>(cells);
        List<OutlineQuad> out = new ArrayList<>();

        while (!remaining.isEmpty()) {
            long seed = remaining.iterator().next();
            int u0 = PatchOutlineBuilder.unpackU(seed);
            int v0 = PatchOutlineBuilder.unpackV(seed);

            // 1) 扩展宽度（沿 u）
            int u1 = u0 + 1;
            while (remaining.contains(PatchOutlineBuilder.pack(u1, v0))) {
                u1++;
            }

            // 2) 扩展高度（沿 v），要求每一行的 [u0,u1) 都存在
            int v1 = v0 + 1;
            outer:
            while (true) {
                for (int u = u0; u < u1; u++) {
                    if (!remaining.contains(PatchOutlineBuilder.pack(u, v1))) {
                        break outer;
                    }
                }
                v1++;
            }

            // 3) 删除矩形区域
            for (int v = v0; v < v1; v++) {
                for (int u = u0; u < u1; u++) {
                    remaining.remove(PatchOutlineBuilder.pack(u, v));
                }
            }

            out.add(new OutlineQuad(dir, d, u0, v0, u1, v1));
        }

        return out;
    }
}

