package com.formacraft.client.preview;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.formacraft.common.preview.OutlineBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * 预览状态管理
 * 客户端缓存预览线框数据；跨网络线程与渲染线程通过不可变快照 + volatile 发布。
 */
public final class OutlinePreviewState {
    /** Soft cap on merged detail boxes; overflow falls back to overall AABB (no top truncation). */
    static final int MAX_MERGED_BOXES = 2500;

    public record Snapshot(
            List<OutlineBlock> blocks,
            List<Box> mergedBoxes,
            Box overallBounds,
            boolean active
    ) {
        public Snapshot {
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            mergedBoxes = mergedBoxes == null ? List.of() : List.copyOf(mergedBoxes);
        }

        public static final Snapshot EMPTY = new Snapshot(List.of(), List.of(), null, false);
    }

    private static volatile Snapshot snapshot = Snapshot.EMPTY;

    private OutlinePreviewState() {}

    public static Snapshot snapshot() {
        return snapshot;
    }

    public static boolean isActive() {
        return snapshot.active;
    }

    public static void clear() {
        snapshot = Snapshot.EMPTY;
    }

    public static void setBlocks(List<OutlineBlock> newBlocks) {
        if (newBlocks == null || newBlocks.isEmpty()) {
            snapshot = Snapshot.EMPTY;
            return;
        }
        List<OutlineBlock> copy = List.copyOf(newBlocks);
        Box overall = computeOverallBounds(copy);
        List<Box> merged = greedyMergeToBoxes(copy, overall, MAX_MERGED_BOXES);
        snapshot = new Snapshot(copy, merged, overall, true);
    }

    static Box computeOverallBounds(List<OutlineBlock> blocks) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        boolean any = false;
        for (OutlineBlock ob : blocks) {
            if (ob == null || ob.pos == null) continue;
            BlockPos p = ob.pos;
            any = true;
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }
        if (!any) return null;
        return new Box(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1).expand(0.02);
    }

    /**
     * 2D 贪婪合并（按每层 y）。超过 {@link #MAX_MERGED_BOXES} 时退化为 overall AABB，
     * 避免只返回下部层、静默丢掉高层轮廓。
     */
    static List<Box> greedyMergeToBoxes(List<OutlineBlock> blocks, Box overallBounds) {
        return greedyMergeToBoxes(blocks, overallBounds, MAX_MERGED_BOXES);
    }

    static List<Box> greedyMergeToBoxes(List<OutlineBlock> blocks, Box overallBounds, int maxMergedBoxes) {
        List<Box> out = new ArrayList<>();
        if (blocks == null || blocks.isEmpty()) return List.of();
        int limit = Math.max(1, maxMergedBoxes);

        Map<Integer, Set<Long>> layers = new HashMap<>();
        for (OutlineBlock ob : blocks) {
            if (ob == null || ob.pos == null) continue;
            BlockPos p = ob.pos;
            long xz = (((long) p.getX()) << 32) ^ (p.getZ() & 0xffffffffL);
            layers.computeIfAbsent(p.getY(), k -> new HashSet<>()).add(xz);
        }

        for (Map.Entry<Integer, Set<Long>> e : layers.entrySet()) {
            int y = e.getKey();
            Set<Long> set = e.getValue();
            while (!set.isEmpty()) {
                if (out.size() >= limit) {
                    // Degenerate: one envelope for the whole building (includes upper floors).
                    return overallBounds != null ? List.of(overallBounds) : List.of();
                }

                long seed = set.iterator().next();
                int x0 = (int) (seed >> 32);
                int z0 = (int) seed;

                int x1 = x0;
                while (set.contains(((((long) (x1 + 1)) << 32) ^ (z0 & 0xffffffffL)))) {
                    x1++;
                }

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

                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        long key = (((long) x) << 32) ^ (z & 0xffffffffL);
                        set.remove(key);
                    }
                }

                out.add(new Box(x0, y, z0, x1 + 1, y + 1, z1 + 1).expand(0.01));
            }
        }

        return List.copyOf(out);
    }
}
