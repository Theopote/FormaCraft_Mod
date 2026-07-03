package com.formacraft.common.tool;

import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.buildcontext.SelectionBox;
import com.formacraft.common.model.constraint.ProtectedZone;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 工具约束快照：client 采集当前选区/轮廓/路径/禁区/对称状态后传入 common/server，
 * common 侧不再反向 import client 工具类。
 */
public final class ToolConstraintSnapshot {

    public final SelectionBox selection;
    public final OutlineShape outline;
    public final List<List<Vec3d>> paths;
    public final List<ProtectedZone> protectedZones;
    public final SymmetryConstraint symmetry;

    public ToolConstraintSnapshot(
            SelectionBox selection,
            OutlineShape outline,
            List<List<Vec3d>> paths,
            List<ProtectedZone> protectedZones,
            SymmetryConstraint symmetry
    ) {
        this.selection = selection;
        this.outline = outline;
        this.paths = paths != null
                ? paths.stream().map(p -> p != null ? List.copyOf(p) : List.<Vec3d>of()).collect(Collectors.toList())
                : List.of();
        this.protectedZones = protectedZones != null ? List.copyOf(protectedZones) : List.of();
        this.symmetry = symmetry != null ? symmetry : SymmetryConstraint.none();
    }

    public static ToolConstraintSnapshot empty() {
        return new ToolConstraintSnapshot(null, null, List.of(), List.of(), SymmetryConstraint.none());
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

    public boolean hasSymmetry() {
        return symmetry != null && symmetry.mode() != MirrorSymmetryMode.NONE;
    }

    /**
     * 对称轴约束。
     */
    public record SymmetryConstraint(
            MirrorSymmetryMode mode,
            BlockPos axisA,
            BlockPos axisB
    ) {
        public static SymmetryConstraint none() {
            return new SymmetryConstraint(MirrorSymmetryMode.NONE, null, null);
        }

        public boolean hasAxis() {
            return axisA != null && axisB != null;
        }
    }
}
