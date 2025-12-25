package com.formacraft.server.skeleton.compound;

import com.formacraft.common.skeleton.SkeletonPlan;
import com.formacraft.server.build.PlannedBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * A minimal dispatcher that knows how to interpret a child plan.
 * Generators can implement this to wire specific interpreters/palettes.
 */
@FunctionalInterface
public interface PlanDispatcher {
    List<PlannedBlock> interpretChild(SkeletonPlan plan, BlockPos origin, ServerWorld world);
}


