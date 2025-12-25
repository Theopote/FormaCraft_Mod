package com.formacraft.common.skeleton.span;

import com.formacraft.common.skeleton.SkeletonPlan;
import com.formacraft.common.skeleton.SkeletonType;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * SpanSuspensionPlan: a straight span along a facing direction.
 * Stores per-step deck center positions (with Y already terrain-adjusted),
 * plus tower indices and per-step cable Y for hangers.
 */
public final class SpanSuspensionPlan implements SkeletonPlan {
    public final List<BlockPos> deckCenters; // size = span+1
    public final int deckHalfWidth;
    public final int towerIndex1;
    public final int towerIndex2;
    public final int towerHeight;
    public final int[] cableY;              // size = span+1
    public final boolean refined;

    public SpanSuspensionPlan(List<BlockPos> deckCenters, int deckHalfWidth, int towerIndex1, int towerIndex2, int towerHeight, int[] cableY, boolean refined) {
        this.deckCenters = deckCenters;
        this.deckHalfWidth = deckHalfWidth;
        this.towerIndex1 = towerIndex1;
        this.towerIndex2 = towerIndex2;
        this.towerHeight = towerHeight;
        this.cableY = cableY;
        this.refined = refined;
    }

    @Override
    public SkeletonType type() {
        return SkeletonType.SPAN_SUSPENSION;
    }
}


