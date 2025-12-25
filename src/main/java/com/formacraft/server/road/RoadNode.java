package com.formacraft.server.road;

import net.minecraft.util.math.BlockPos;

/**
 * RoadNode for A*.
 */
public final class RoadNode {
    public final BlockPos pos;
    public final int gCost;
    public final int hCost;
    public final RoadNode parent;

    public RoadNode(BlockPos pos, int gCost, int hCost, RoadNode parent) {
        this.pos = pos;
        this.gCost = gCost;
        this.hCost = hCost;
        this.parent = parent;
    }

    public int fCost() {
        return gCost + hCost;
    }
}


