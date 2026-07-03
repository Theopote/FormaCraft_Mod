package com.formacraft.server.skeleton;

import com.formacraft.common.skeleton.SkeletonPlan;
import com.formacraft.common.build.PlannedBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Interpreter turns a SkeletonPlan into concrete PlannedBlocks.
 * This keeps generators deterministic and makes topology reusable across archetypes.
 */
public interface SkeletonInterpreter<P extends SkeletonPlan> {
    List<PlannedBlock> interpret(P plan, BlockPos origin, ServerWorld world);
}


