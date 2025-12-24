package com.formacraft.client.preview;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * 预览状态管理
 * 客户端缓存预览线框数据
 */
public class OutlinePreviewState {
    public static List<OutlineBlock> blocks = new ArrayList<>();
    /** 合并后的外形盒子（用于更“整体”的轮廓渲染） */
    public static List<Box> mergedBoxes = new ArrayList<>();
    public static boolean active = false;

    public static void clear() {
        blocks.clear();
        mergedBoxes.clear();
        active = false;
    }

    public static void setBlocks(List<OutlineBlock> newBlocks) {
        blocks = newBlocks != null ? new ArrayList<>(newBlocks) : new ArrayList<>();
        active = !blocks.isEmpty();
        mergedBoxes = greedyMergeToBoxes(blocks);
    }

    /**
     * 2D 贪婪合并（按每层 y）：将相邻的方块合并为更少的长方体盒子，减少“密密麻麻小框”。
     * 上限用于避免极端情况下合并结果过多导致卡顿。
     */
    private static List<Box> greedyMergeToBoxes(List<OutlineBlock> blocks) {
        List<Box> out = new ArrayList<>();
        if (blocks == null || blocks.isEmpty()) return out;

        // y -> set of (x,z) packed
        Map<Integer, Set<Long>> layers = new HashMap<>();
        for (OutlineBlock ob : blocks) {
            if (ob == null || ob.pos == null) continue;
            BlockPos p = ob.pos;
            int y = p.getY();
            long xz = (((long) p.getX()) << 32) ^ (p.getZ() & 0xffffffffL);
            layers.computeIfAbsent(y, k -> new HashSet<>()).add(xz);
        }

        for (Map.Entry<Integer, Set<Long>> e : layers.entrySet()) {
            int y = e.getKey();
            Set<Long> set = e.getValue();
            while (!set.isEmpty()) {
                if (out.size() >= 2500) return out;

                long seed = set.iterator().next();
                int x0 = (int) (seed >> 32);
                int z0 = (int) seed;

                // expand X
                int x1 = x0;
                while (set.contains(((((long) (x1 + 1)) << 32) ^ (z0 & 0xffffffffL)))) {
                    x1++;
                }

                // expand Z while full row exists
                int z1 = z0;
                outer:
                while (true) {
                    int nz = z1 + 1;
                    for (int x = x0; x <= x1; x++) {
                        long key = (((long) x) << 32) ^ (nz & 0xffffffffL);
                        if (!set.contains(key)) break outer;
                    }
                    z1 = nz;
                }

                // remove rectangle
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        long key = (((long) x) << 32) ^ (z & 0xffffffffL);
                        set.remove(key);
                    }
                }

                // box uses [min, max+1]
                out.add(new Box(x0, y, z0, x1 + 1, y + 1, z1 + 1).expand(0.01));
            }
        }

        return out;
    }
}

