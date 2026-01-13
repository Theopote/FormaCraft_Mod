package com.formacraft.common.component.variant;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * VoxelGrid（体素网格）v1：变体编译器的中间表示。
 * <p>
 * 职责：
 * - 存储所有 Voxel
 * - 提供按 segment tag 或 semantic tag 过滤查询
 * - 支持批量变换（旋转/镜像/偏移）
 */
public final class VoxelGrid {
    private final List<Voxel> voxels = new ArrayList<>();

    public void add(Voxel v) {
        if (v != null) voxels.add(v);
    }

    public void addAll(List<Voxel> vs) {
        if (vs != null) voxels.addAll(vs);
    }

    public List<Voxel> all() {
        return new ArrayList<>(voxels);
    }

    public int size() {
        return voxels.size();
    }

    /**
     * 按 segment tag 过滤（例如 "SEG_MID_Y"）。
     */
    public List<Voxel> filterBySegmentTag(String tag) {
        if (tag == null || tag.isBlank()) return List.of();
        String t = tag.trim().toUpperCase();
        return voxels.stream()
                .filter(v -> v.hasSegmentTag(t))
                .collect(Collectors.toList());
    }

    /**
     * 按 semantic tag 过滤（例如 "FRAME"）。
     */
    public List<Voxel> filterBySemanticTag(String tag) {
        if (tag == null || tag.isBlank()) return List.of();
        String t = tag.trim().toUpperCase();
        return voxels.stream()
                .filter(v -> v.hasSemanticTag(t))
                .collect(Collectors.toList());
    }

    /**
     * 获取指定轴的范围（最小值、最大值）。
     */
    public int[] getAxisRange(Voxel.Axis axis) {
        if (voxels.isEmpty()) return new int[]{0, 0};
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (Voxel v : voxels) {
            int coord = switch (axis) {
                case X -> v.x();
                case Y -> v.y();
                case Z -> v.z();
            };
            if (coord < min) min = coord;
            if (coord > max) max = coord;
        }
        return new int[]{min, max};
    }

    /**
     * 清空网格（用于重建）。
     */
    public void clear() {
        voxels.clear();
    }

    @Override
    public String toString() {
        return String.format("VoxelGrid[%d voxels]", voxels.size());
    }
}
