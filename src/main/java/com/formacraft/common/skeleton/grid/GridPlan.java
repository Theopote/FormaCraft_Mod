package com.formacraft.common.skeleton.grid;

import com.formacraft.common.skeleton.SkeletonType;
import com.formacraft.common.skeleton.SkeletonPlan;
import com.formacraft.common.skeleton.transform.BlockTransform;

import java.util.ArrayList;
import java.util.List;

/**
 * GridPlan: repeat a module with a list of transforms (grid cell placements).
 * The module itself is carried as a child plan (often GeneratorBackedPlan).
 */
public final class GridPlan extends SkeletonPlan {
    public final SkeletonPlan module;
    public final List<BlockTransform> placements = new ArrayList<>();

    public GridPlan(SkeletonPlan module) {
        super();
        this.module = module;
    }

    @Override
    public SkeletonType type() {
        return SkeletonType.GRID;
    }
}


