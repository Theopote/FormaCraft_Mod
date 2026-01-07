package com.formacraft.common.skeleton.linear;

import com.formacraft.common.skeleton.SkeletonPlan;
import com.formacraft.common.skeleton.SkeletonType;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * A linear path plan represented as a list of anchor-relative points.
 * Points are in world coords at generation time to support terrain following.
 */
public final class LinearPathPlan extends SkeletonPlan {
    public final List<BlockPos> pathPoints;
    public final int thickness;
    public final int height;
    public final int towerSpacing;
    public final boolean crenels;

    public LinearPathPlan(List<BlockPos> pathPoints, int thickness, int height, int towerSpacing, boolean crenels) {
        super();
        this.pathPoints = pathPoints;
        this.thickness = thickness;
        this.height = height;
        this.towerSpacing = towerSpacing;
        this.crenels = crenels;
    }

    @Override
    public SkeletonType type() {
        return SkeletonType.LINEAR_PATH;
    }
}


