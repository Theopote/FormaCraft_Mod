package com.formacraft.server.skeleton.compound;

import com.formacraft.common.skeleton.SkeletonPlan;
import com.formacraft.common.skeleton.compound.CompoundPlan;
import com.formacraft.common.skeleton.transform.BlockTransform;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.skeleton.SkeletonInterpreter;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * CompoundInterpreter:
 * - Delegates interpretation of child plans to a dispatcher (injectable)
 * - Applies each component transform (relative) then merges blocks
 *
 * NOTE: This doesn't enforce deduplication. Preview/merge layers can handle that if needed.
 */
public final class CompoundInterpreter implements SkeletonInterpreter<CompoundPlan> {
    private final PlanDispatcher dispatcher;

    public CompoundInterpreter(PlanDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public List<PlannedBlock> interpret(CompoundPlan plan, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> out = new ArrayList<>(Math.max(2000, plan.components.size() * 3000));
        for (CompoundPlan.Component c : plan.components) {
            SkeletonPlan child = c.plan;
            BlockTransform tx = c.transform;
            if (child == null) continue;
            List<PlannedBlock> childBlocks = dispatcher.interpretChild(child, origin, world);
            if (childBlocks == null || childBlocks.isEmpty()) continue;
            for (PlannedBlock pb : childBlocks) {
                BlockPos rel = pb.getPos().subtract(origin);
                BlockPos rel2 = tx.apply(rel);
                out.add(new PlannedBlock(origin.add(rel2), pb.getTargetState()));
            }
        }
        return out;
    }
}


