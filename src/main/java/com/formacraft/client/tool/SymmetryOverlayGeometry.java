package com.formacraft.client.tool;

import com.formacraft.common.buildcontext.SelectionBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.ArrayList;
import java.util.List;

/** Coordinates describe block centres, matching the mirror filters rather than a decorative axis. */
final class SymmetryOverlayGeometry {
    record Plane(Vec3d corner, Vec3d u, Vec3d v, int red, int green, int blue) {}

    static List<Plane> planes(SymmetryMode mode, SelectionBox selection, BlockPos origin,
                              BlockPos axisA, BlockPos axisB) {
        if (mode == SymmetryMode.NONE) return List.of();
        BlockPos min = selection == null ? origin.add(-8, -8, -8) : selection.min();
        BlockPos max = selection == null ? origin.add(8, 8, 8) : selection.max();
        double cx = selection == null ? origin.getX() + 0.5 : (min.getX() + max.getX() + 1) / 2 + 0.5;
        double cy = selection == null ? origin.getY() + 0.5 : (min.getY() + max.getY() + 1) / 2 + 0.5;
        double cz = selection == null ? origin.getZ() + 0.5 : (min.getZ() + max.getZ() + 1) / 2 + 0.5;
        double x0 = min.getX() - 1, x1 = max.getX() + 2;
        double z0 = min.getZ() - 1, z1 = max.getZ() + 2;
        double y0 = min.getY(), y1 = Math.max(max.getY() + 1, y0 + 12);
        List<Plane> result = new ArrayList<>();
        if (mode == SymmetryMode.MIRROR_X || mode == SymmetryMode.BOTH)
            result.add(new Plane(new Vec3d(cx, y0, z0), new Vec3d(0, 0, z1 - z0), new Vec3d(0, y1 - y0, 0), 255, 85, 85));
        if (mode == SymmetryMode.MIRROR_Y)
            result.add(new Plane(new Vec3d(x0, cy, z0), new Vec3d(x1 - x0, 0, 0), new Vec3d(0, 0, z1 - z0), 85, 255, 130));
        if (mode == SymmetryMode.MIRROR_Z || mode == SymmetryMode.BOTH)
            result.add(new Plane(new Vec3d(x0, y0, cz), new Vec3d(x1 - x0, 0, 0), new Vec3d(0, y1 - y0, 0), 85, 150, 255));
        if (mode == SymmetryMode.CUSTOM_AXIS && axisA != null && axisB != null && !ToolGeometry.sameXZ(axisA, axisB)) {
            // The existing custom-axis filter snaps to the dominant X/Z direction.
            if (Math.abs(axisB.getX() - axisA.getX()) < Math.abs(axisB.getZ() - axisA.getZ())) {
                double x = (axisA.getX() + axisB.getX()) / 2 + 0.5;
                result.add(new Plane(new Vec3d(x, y0, Math.min(z0, Math.min(axisA.getZ(), axisB.getZ()))),
                        new Vec3d(0, 0, Math.max(z1, Math.max(axisA.getZ(), axisB.getZ()) + 1) - Math.min(z0, Math.min(axisA.getZ(), axisB.getZ()))),
                        new Vec3d(0, y1 - y0, 0), 220, 120, 255));
            } else {
                double z = (axisA.getZ() + axisB.getZ()) / 2 + 0.5;
                result.add(new Plane(new Vec3d(Math.min(x0, Math.min(axisA.getX(), axisB.getX())), y0, z),
                        new Vec3d(Math.max(x1, Math.max(axisA.getX(), axisB.getX()) + 1) - Math.min(x0, Math.min(axisA.getX(), axisB.getX())), 0, 0),
                        new Vec3d(0, y1 - y0, 0), 220, 120, 255));
            }
        }
        return result;
    }
}
