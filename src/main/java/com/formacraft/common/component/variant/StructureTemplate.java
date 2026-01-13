package com.formacraft.common.component.variant;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * StructureTemplate（结构模板）v1：变体编译器的输入抽象。
 * <p>
 * 职责：
 * - 提供"原始"体素列表（从 component.json / NBT / etc. 加载而来）
 * - 支持按 segment tag 分组查询（例如 "SEG_START" / "SEG_MID_Y" / "SEG_END"）
 * - 支持按 semantic tag 分组查询（例如 "FRAME" / "PANEL"）
 * <p>
 * 注意：StructureTemplate 不做变换，只负责"读取与分类"；变换由 SegmentScaler / TransformApplier 负责。
 */
public final class StructureTemplate {
    private final List<Voxel> voxels = new ArrayList<>();
    private int originW, originH, originD; // 原始尺寸（用于启发式推断 segment）

    public StructureTemplate(List<Voxel> voxels, int originW, int originH, int originD) {
        if (voxels != null) this.voxels.addAll(voxels);
        this.originW = originW;
        this.originH = originH;
        this.originD = originD;
    }

    public List<Voxel> all() {
        return new ArrayList<>(voxels);
    }

    public int originWidth() { return originW; }
    public int originHeight() { return originH; }
    public int originDepth() { return originD; }

    /**
     * 按 segment tag 分组查询（例如 "SEG_MID_Y"）。
     * <p>
     * v1：如果结构里没有显式标注 segment tag，会尝试基于坐标做启发式推断：
     * - Y 轴：最下层 = SEG_START, 最上层 = SEG_END, 中间层 = SEG_MID_Y
     * - X/Z 轴：类似
     */
    public List<Voxel> getSegment(Voxel.Axis axis, String segmentTag) {
        if (segmentTag == null || segmentTag.isBlank()) return List.of();
        String tag = segmentTag.trim().toUpperCase();

        // 1. 显式标注的优先
        List<Voxel> explicit = voxels.stream()
                .filter(v -> v.hasSegmentTag(tag))
                .collect(Collectors.toList());
        if (!explicit.isEmpty()) return explicit;

        // 2. 启发式推断（v1 简化规则）
        return inferSegment(axis, tag);
    }

    /**
     * 启发式推断分段（v1）。
     * <p>
     * 策略：
     * - SEG_START: 轴上最小坐标的切片
     * - SEG_END: 轴上最大坐标的切片
     * - SEG_MID_X/Y/Z: 中间所有切片（不包括 START 和 END）
     */
    private List<Voxel> inferSegment(Voxel.Axis axis, String tag) {
        if (voxels.isEmpty()) return List.of();

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

        if (tag.equals("SEG_START")) {
            int finalMin = min;
            return voxels.stream()
                    .filter(v -> coordOf(v, axis) == finalMin)
                    .collect(Collectors.toList());
        }

        if (tag.equals("SEG_END")) {
            int finalMax = max;
            return voxels.stream()
                    .filter(v -> coordOf(v, axis) == finalMax)
                    .collect(Collectors.toList());
        }

        if (tag.equals("SEG_MID_X") || tag.equals("SEG_MID_Y") || tag.equals("SEG_MID_Z")) {
            int finalMin = min;
            int finalMax = max;
            return voxels.stream()
                    .filter(v -> {
                        int c = coordOf(v, axis);
                        return c > finalMin && c < finalMax;
                    })
                    .collect(Collectors.toList());
        }

        return List.of();
    }

    private int coordOf(Voxel v, Voxel.Axis axis) {
        return switch (axis) {
            case X -> v.x();
            case Y -> v.y();
            case Z -> v.z();
        };
    }

    @Override
    public String toString() {
        return String.format("StructureTemplate[%d voxels, size=%dx%dx%d]",
                voxels.size(), originW, originH, originD);
    }
}
