package com.formacraft.common.tool;

import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.buildcontext.SelectionBox;
import com.formacraft.common.model.constraint.ProtectedZone;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * 工具约束快照：client 采集当前选区/轮廓/禁区/对称状态后传入 common/server，
 * common 侧不再反向 import client 工具类。
 */
public final class ToolConstraintSnapshot {

    public final SelectionBox selection;
    public final OutlineShape outline;
    public final List<ProtectedZone> protectedZones;
    public final SymmetryConstraint symmetry;

    public ToolConstraintSnapshot(
            SelectionBox selection,
            OutlineShape outline,
            List<ProtectedZone> protectedZones,
            SymmetryConstraint symmetry
    ) {
        this.selection = selection;
        this.outline = outline;
        this.protectedZones = protectedZones != null ? List.copyOf(protectedZones) : List.of();
        this.symmetry = symmetry != null ? symmetry : SymmetryConstraint.none();
    }

    public static ToolConstraintSnapshot empty() {
        return new ToolConstraintSnapshot(null, null, List.of(), SymmetryConstraint.none());
    }

    public boolean hasSelection() {
        return selection != null && selection.min() != null && selection.max() != null;
    }

    public boolean hasOutline() {
        return outline != null;
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
