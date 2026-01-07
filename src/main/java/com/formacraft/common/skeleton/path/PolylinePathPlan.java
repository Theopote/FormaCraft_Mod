package com.formacraft.common.skeleton.path;

import com.formacraft.common.skeleton.SkeletonPlan;
import com.formacraft.common.skeleton.SkeletonType;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * PolylinePathPlan: a polyline path in coordinates RELATIVE to the interpreter origin.
 */
public final class PolylinePathPlan extends SkeletonPlan {
    public final List<BlockPos> points; // relative coords (x,y,z) to origin
    public final int width;
    public final boolean followTerrain;
    public final boolean lamps;
    public final int lampInterval;

    public PolylinePathPlan(List<BlockPos> points, int width, boolean followTerrain, boolean lamps, int lampInterval) {
        super();
        this.points = points;
        this.width = Math.max(1, width);
        this.followTerrain = followTerrain;
        this.lamps = lamps;
        this.lampInterval = Math.max(4, lampInterval);
    }

    @Override
    public SkeletonType type() {
        return SkeletonType.PATH_POLYLINE;
    }
}


