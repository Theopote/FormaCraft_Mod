package com.formacraft.common.tool;

import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.buildcontext.SelectionBox;
import com.formacraft.common.model.constraint.ProtectedZone;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 工具约束快照：client 采集当前选区/轮廓/路径/禁区状态后传入 common，
 * common 侧不再反向 import client 工具类。
 *
 * <p>对称约束不在此快照中：对称是**规划**约束，唯一载体是
 * {@code FormaRequest.symmetryMode}（→ 后端提示词）。曾在此镜像一份的
 * 快照对称字段只有写入、没有读取者，已删除以避免出现两个对称真相源。
 */
public final class ToolConstraintSnapshot {

    public final SelectionBox selection;
    public final OutlineShape outline;
    public final List<List<Vec3d>> paths;
    public final List<ProtectedZone> protectedZones;

    public ToolConstraintSnapshot(
            SelectionBox selection,
            OutlineShape outline,
            List<List<Vec3d>> paths,
            List<ProtectedZone> protectedZones
    ) {
        this.selection = selection;
        this.outline = outline;
        this.paths = paths != null
                ? paths.stream().map(p -> p != null ? List.copyOf(p) : List.<Vec3d>of()).collect(Collectors.toList())
                : List.of();
        this.protectedZones = protectedZones != null ? List.copyOf(protectedZones) : List.of();
    }

    public static ToolConstraintSnapshot empty() {
        return new ToolConstraintSnapshot(null, null, List.of(), List.of());
    }

    public boolean hasSelection() {
        return selection != null && selection.min() != null && selection.max() != null;
    }

    public boolean hasOutline() {
        return outline != null;
    }

    public boolean hasPaths() {
        return !paths.isEmpty();
    }

    public boolean hasForbiddenZone() {
        return !protectedZones.isEmpty();
    }
}
