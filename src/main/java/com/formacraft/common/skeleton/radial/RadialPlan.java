package com.formacraft.common.skeleton.radial;

import com.formacraft.common.skeleton.SkeletonPlan;
import com.formacraft.common.skeleton.SkeletonType;

import java.util.ArrayList;
import java.util.List;

/**
 * A plan consisting of radial primitives.
 */
public final class RadialPlan extends SkeletonPlan {
    public final List<RadialPrimitive> primitives = new ArrayList<>();

    public RadialPlan() {
        super();
    }

    @Override
    public SkeletonType type() {
        return SkeletonType.RADIAL_RING;
    }

    public RadialPlan add(RadialPrimitive p) {
        primitives.add(p);
        return this;
    }
}


