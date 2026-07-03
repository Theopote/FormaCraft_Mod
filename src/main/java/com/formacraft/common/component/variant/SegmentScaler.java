package com.formacraft.common.component.variant;

import com.formacraft.common.component.model.ComponentPrototype;
import com.formacraft.common.component.model.PersistedComponentVariant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SegmentScaler（分段缩放器）v1：核心算法。
 * <p>
 * 职责：
 * - 按 variant_rules.scaling 规则对结构模板做分段缩放
 * - 支持三种模式：REPEAT（重复中段）/ TRIM（裁剪中段）/ FIXED（固定不变）
 * <p>
 * 算法核心思想（以 Y 轴为例）：
 * <pre>
 * [ START ][ MID ][ MID ][ MID ][ END ]
 *
 * - START：只出现 1 次（固定）
 * - MID：重复 N 次（N = targetSize - START.size - END.size）
 * - END：只出现 1 次（固定）
 * </pre>
 */
public final class SegmentScaler {
    private SegmentScaler() {}

    /**
     * 应用分段缩放（核心入口）。
     * <p>
     * 策略：
     * - 依次处理 X / Y / Z 三轴
     * - 如果某轴没有 scaling rule 或 rule.type="FIXED"，则跳过
     * - 否则按 REPEAT / TRIM 规则重建该轴的 voxel 分布
     */
    public static VoxelGrid applyScaling(
            StructureTemplate tpl,
            ComponentPrototype.VariantRules.Scaling rules,
            PersistedComponentVariant.Params.Scale scale
    ) {
        if (tpl == null || rules == null || scale == null) {
            // 无规则时，原样输出
            VoxelGrid grid = new VoxelGrid();
            if (tpl != null) grid.addAll(tpl.all());
            return grid;
        }

        // v1 策略：每个轴独立处理（后续可改为"联合缩放"）
        VoxelGrid result = new VoxelGrid();
        result.addAll(tpl.all()); // 先拷贝原始 voxel

        // 依次处理 X / Y / Z
        for (Voxel.Axis axis : Voxel.Axis.values()) {
            ComponentPrototype.VariantRules.Scaling.AxisRule rule = getAxisRule(rules, axis);
            if (rule == null || "FIXED".equalsIgnoreCase(rule.type)) continue;

            int targetSize = getTargetSize(scale, axis);
            if (rule.min != null && targetSize < rule.min) targetSize = rule.min;
            if (rule.max != null && targetSize > rule.max) targetSize = rule.max;

            result = scaleAxis(result, tpl, axis, rule, targetSize);
        }

        return result;
    }

    /**
     * 对单个轴应用缩放。
     */
    private static VoxelGrid scaleAxis(
            VoxelGrid current,
            StructureTemplate tpl,
            Voxel.Axis axis,
            ComponentPrototype.VariantRules.Scaling.AxisRule rule,
            int targetSize
    ) {
        String segmentTag = rule.segment != null ? rule.segment : ("SEG_MID_" + axis.name());

        List<Voxel> start = tpl.getSegment(axis, "SEG_START");
        List<Voxel> mid = tpl.getSegment(axis, segmentTag);
        List<Voxel> end = tpl.getSegment(axis, "SEG_END");

        // 如果没有显式分段，尝试用整个结构（兼容简单构件）
        if (start.isEmpty() && mid.isEmpty() && end.isEmpty()) {
            mid = tpl.all();
        }

        int startSize = sliceSize(start, axis);
        int endSize = sliceSize(end, axis);
        int baseSize = startSize + endSize;
        int midRepeatCount = Math.max(0, targetSize - baseSize);

        VoxelGrid out = new VoxelGrid();
        int cursor = 0;

        // 1) START 段（固定出现 1 次）
        out.addAll(shiftVoxels(start, axis, cursor));
        cursor += startSize;

        // 2) MID 段（重复 midRepeatCount 次）
        for (int i = 0; i < midRepeatCount; i++) {
            out.addAll(shiftVoxels(mid, axis, cursor));
            cursor += 1; // 每次重复偏移 1（假设 mid 是单层切片）
        }

        // 3) END 段（固定出现 1 次）
        out.addAll(shiftVoxels(end, axis, cursor));

        return out;
    }

    /**
     * 获取某轴的切片厚度（单位：方块数）。
     * <p>
     * v1 简化：假设切片厚度 = max(coord) - min(coord) + 1
     */
    private static int sliceSize(List<Voxel> voxels, Voxel.Axis axis) {
        if (voxels.isEmpty()) return 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (Voxel v : voxels) {
            int c = coordOf(v, axis);
            if (c < min) min = c;
            if (c > max) max = c;
        }
        return max - min + 1;
    }

    /**
     * 在指定轴上偏移一组 voxel。
     */
    private static List<Voxel> shiftVoxels(List<Voxel> src, Voxel.Axis axis, int offset) {
        List<Voxel> out = new ArrayList<>();
        for (Voxel v : src) {
            out.add(v.shift(axis, offset));
        }
        return out;
    }

    private static ComponentPrototype.VariantRules.Scaling.AxisRule getAxisRule(
            ComponentPrototype.VariantRules.Scaling rules,
            Voxel.Axis axis
    ) {
        if (rules == null || rules.axes == null) return null;
        return rules.axes.get(axis.name());
    }

    private static int getTargetSize(PersistedComponentVariant.Params.Scale scale, Voxel.Axis axis) {
        if (scale == null) return 0;
        return switch (axis) {
            case X -> scale.x;
            case Y -> scale.y;
            case Z -> scale.z;
        };
    }

    private static int coordOf(Voxel v, Voxel.Axis axis) {
        return switch (axis) {
            case X -> v.x();
            case Y -> v.y();
            case Z -> v.z();
        };
    }
}
