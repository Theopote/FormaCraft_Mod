package com.formacraft.server.cluster.layout;

import net.minecraft.util.math.BlockPos;

/**
 * Candidate: a potential placement for a unit.
 *
 * originRel is relative to the cluster anchor (global origin).
 * For generators in FormaCraft, origin typically behaves as the "min corner" of the footprint.
 */
public final class Candidate {
    public final BuildingUnit unit;
    public final BlockPos originRel; // (dx,0,dz)
    public final int rotation; // 0/90/180/270
    public final double score;

    public final int flattenCost;
    public final float slopeAvg;
    public final int range;

    public Candidate(BuildingUnit unit,
                     BlockPos originRel,
                     int rotation,
                     double score,
                     int flattenCost,
                     float slopeAvg,
                     int range) {
        this.unit = unit;
        this.originRel = originRel;
        this.rotation = rotation;
        this.score = score;
        this.flattenCost = flattenCost;
        this.slopeAvg = slopeAvg;
        this.range = range;
    }
}


