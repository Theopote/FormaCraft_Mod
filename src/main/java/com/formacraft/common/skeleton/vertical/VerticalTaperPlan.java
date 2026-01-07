package com.formacraft.common.skeleton.vertical;

import com.formacraft.common.skeleton.SkeletonPlan;
import com.formacraft.common.skeleton.SkeletonType;

import java.util.List;

/**
 * VerticalTaperPlan: per-y half-size for a vertical taper structure,
 * plus optional platform levels and spire span.
 */
public final class VerticalTaperPlan extends SkeletonPlan {
    public final int height;
    public final int baseHalf;
    public final int topHalf;
    public final int[] halfByY;            // size = height+1
    public final List<Integer> platformsY; // platform base Y values
    public final boolean refined;
    public final int spireStartY;
    public final int spireEndY;

    public VerticalTaperPlan(int height, int baseHalf, int topHalf, int[] halfByY, List<Integer> platformsY, boolean refined, int spireStartY, int spireEndY) {
        super();
        this.height = height;
        this.baseHalf = baseHalf;
        this.topHalf = topHalf;
        this.halfByY = halfByY;
        this.platformsY = platformsY;
        this.refined = refined;
        this.spireStartY = spireStartY;
        this.spireEndY = spireEndY;
    }

    @Override
    public SkeletonType type() {
        return SkeletonType.VERTICAL_TAPER;
    }
}


