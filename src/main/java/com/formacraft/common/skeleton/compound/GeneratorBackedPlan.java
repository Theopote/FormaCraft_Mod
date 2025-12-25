package com.formacraft.common.skeleton.compound;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.skeleton.SkeletonPlan;
import com.formacraft.common.skeleton.SkeletonType;

/**
 * A SkeletonPlan wrapper that delegates to an existing StructureGenerator via BuildingSpec.
 * This is a pragmatic bridge that lets COMPOUND compose existing generators without rewriting them.
 */
public final class GeneratorBackedPlan implements SkeletonPlan {
    public final BuildingSpec spec;

    public GeneratorBackedPlan(BuildingSpec spec) {
        this.spec = spec;
    }

    @Override
    public SkeletonType type() {
        return SkeletonType.COMPOUND;
    }
}


