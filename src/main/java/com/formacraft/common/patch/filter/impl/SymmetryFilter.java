package com.formacraft.common.patch.filter.impl;

import com.formacraft.common.buildcontext.SelectionBox;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.filter.PatchFilter;
import com.formacraft.common.patch.filter.PatchFilterContext;
import com.formacraft.common.tool.MirrorSymmetryMode;
import com.formacraft.common.tool.ToolConstraintSnapshot;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SymmetryFilter（对称 / 镜像）
 */
public class SymmetryFilter implements PatchFilter {

    @Override
    public List<BlockPatch> filter(
            List<BlockPatch> input,
            BlockPos origin,
            PatchFilterContext context
    ) {
        if (!context.hasSymmetry()) {
            return input;
        }

        ToolConstraintSnapshot.SymmetryConstraint sym = context.snapshot.symmetry;
        MirrorSymmetryMode mode = sym.mode();
        if (mode == null || mode == MirrorSymmetryMode.NONE) {
            return input;
        }

        List<BlockPatch> out = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();

        for (BlockPatch p : input) {
            BlockPos world = origin.add(p.dx(), p.dy(), p.dz());

            if (seen.add(world)) {
                out.add(p);
            }

            BlockPos mirrored = mirror(world, mode, sym, context.snapshot.selection, origin);
            if (mirrored != null && !mirrored.equals(world) && seen.add(mirrored)) {
                out.add(new BlockPatch(
                        p.action(),
                        mirrored.getX() - origin.getX(),
                        mirrored.getY() - origin.getY(),
                        mirrored.getZ() - origin.getZ(),
                        p.targetBlock()
                ));
            }
        }

        return out;
    }

    private BlockPos mirror(
            BlockPos pos,
            MirrorSymmetryMode mode,
            ToolConstraintSnapshot.SymmetryConstraint sym,
            SelectionBox selection,
            BlockPos origin
    ) {
        if (pos == null) {
            return null;
        }

        if (mode == MirrorSymmetryMode.CUSTOM_AXIS && sym.hasAxis()) {
            return mirrorCustomAxis(pos, sym.axisA(), sym.axisB());
        }

        int centerX;
        int centerZ;
        if (selection != null && selection.min() != null && selection.max() != null) {
            BlockPos min = selection.min();
            BlockPos max = selection.max();
            centerX = (min.getX() + max.getX() + 1) / 2;
            centerZ = (min.getZ() + max.getZ() + 1) / 2;
        } else {
            centerX = origin.getX();
            centerZ = origin.getZ();
        }

        BlockPos result = pos;

        if (mode == MirrorSymmetryMode.MIRROR_X || mode == MirrorSymmetryMode.BOTH) {
            result = new BlockPos(
                    centerX * 2 - result.getX(),
                    result.getY(),
                    result.getZ()
            );
        }

        if (mode == MirrorSymmetryMode.MIRROR_Z || mode == MirrorSymmetryMode.BOTH) {
            result = new BlockPos(
                    result.getX(),
                    result.getY(),
                    centerZ * 2 - result.getZ()
            );
        }

        return result;
    }

    private BlockPos mirrorCustomAxis(BlockPos pos, BlockPos axisA, BlockPos axisB) {
        int dx = axisB.getX() - axisA.getX();
        int dz = axisB.getZ() - axisA.getZ();

        if (Math.abs(dx) < Math.abs(dz)) {
            int centerX = (axisA.getX() + axisB.getX()) / 2;
            return new BlockPos(
                    centerX * 2 - pos.getX(),
                    pos.getY(),
                    pos.getZ()
            );
        }

        int centerZ = (axisA.getZ() + axisB.getZ()) / 2;
        return new BlockPos(
                pos.getX(),
                pos.getY(),
                centerZ * 2 - pos.getZ()
        );
    }
}
