package com.formacraft.client.tool;

import com.formacraft.common.buildcontext.BuildContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Pure interaction state; the plane height is the first buildable block Y. */
public final class WorkPlaneState {
    private BlockPos center;
    private boolean locked;
    private boolean followTerrain;

    public boolean hasPlane() { return center != null; }
    public BlockPos center() { return center; }
    public int height() { return center == null ? 0 : center.getY(); }
    public boolean locked() { return hasPlane() && locked; }
    public boolean followsTerrain() { return followTerrain; }
    public boolean fixed() { return locked() && !followTerrain; }
    public void set(BlockPos center) { this.center = center.toImmutable(); }
    public void clear() { center = null; locked = false; followTerrain = false; }
    public void toggleLock() { if (hasPlane()) locked = !locked; }
    public void toggleTerrain() { followTerrain = !followTerrain; }
    public void setHeight(int y, int bottom, int top) {
        if (hasPlane()) center = new BlockPos(center.getX(), Math.clamp(y, bottom, top), center.getZ());
    }

    public BuildContext apply(BuildContext context, boolean modifyingRegion) {
        if (!fixed() || context == null || modifyingRegion || context.restrictToSelection) return context;
        BlockPos origin = context.mode == BuildContext.Mode.IMPLICIT_ANCHOR || context.origin == null ? center : context.origin;
        return context.withOrigin(new BlockPos(origin.getX(), height(), origin.getZ()));
    }

    public static Vec3d intersect(Vec3d origin, Vec3d direction, int y, double reach) {
        if (origin == null || direction == null || direction.lengthSquared() < 1e-12) return null;
        Vec3d ray = direction.normalize();
        if (Math.abs(ray.y) < 1e-6) return null;
        double distance = (y - origin.y) / ray.y;
        if (distance < 1e-6 || distance > reach) return null;
        Vec3d point = origin.add(ray.multiply(distance));
        return new Vec3d(point.x, y, point.z);
    }
}
