package com.formacraft.common.skeleton.compound;

import com.formacraft.common.skeleton.SkeletonPlan;
import com.formacraft.common.skeleton.SkeletonType;
import com.formacraft.common.skeleton.transform.BlockTransform;

import java.util.ArrayList;
import java.util.List;

/**
 * CompoundPlan composes multiple child plans with transforms.
 * This enables axial symmetry, mirroring, tiling, and modular composition.
 */
public final class CompoundPlan extends SkeletonPlan {
    public final List<Component> components = new ArrayList<>();

    public CompoundPlan() {
        super();
    }

    @Override
    public SkeletonType type() {
        return SkeletonType.COMPOUND;
    }

    public CompoundPlan add(SkeletonPlan plan, BlockTransform transform) {
        components.add(new Component(plan, transform == null ? BlockTransform.identity() : transform));
        return this;
    }

    public static final class Component {
        public final SkeletonPlan plan;
        public final BlockTransform transform;

        public Component(SkeletonPlan plan, BlockTransform transform) {
            this.plan = plan;
            this.transform = transform;
        }
    }
}


