package com.formacraft.server.skeleton.compound;

import com.formacraft.common.skeleton.SkeletonPlan;
import com.formacraft.common.skeleton.compound.CompoundPlan;
import com.formacraft.common.skeleton.transform.BlockTransform;
import com.formacraft.common.build.PlannedBlock;
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
    private final boolean protectSiblingSolids;

    public CompoundInterpreter(PlanDispatcher dispatcher) {
        this(dispatcher, false);
    }

    public CompoundInterpreter(PlanDispatcher dispatcher, boolean protectSiblingSolids) {
        this.dispatcher = dispatcher;
        this.protectSiblingSolids = protectSiblingSolids;
    }

    @Override
    public List<PlannedBlock> interpret(CompoundPlan plan, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> out = new ArrayList<>(Math.max(2000, plan.components.size() * 3000));
        java.util.Map<BlockPos, net.minecraft.block.BlockState> occupied = new java.util.HashMap<>();
        for (CompoundPlan.Component c : plan.components) {
            SkeletonPlan child = c.plan;
            BlockTransform tx = c.transform;
            if (child == null) continue;
            List<PlannedBlock> childBlocks = dispatcher.interpretChild(child, origin, world);
            if (childBlocks == null || childBlocks.isEmpty()) continue;
            if (protectSiblingSolids) {
                // Resolve each child's own carving before protecting earlier siblings.
                java.util.Map<BlockPos, PlannedBlock> finalChild = new java.util.LinkedHashMap<>();
                for (PlannedBlock block : childBlocks) finalChild.put(block.getPos(), block);
                childBlocks = new ArrayList<>(finalChild.values());
            }
            for (PlannedBlock pb : childBlocks) {
                BlockPos rel = pb.getPos().subtract(origin);
                BlockPos rel2 = tx.apply(rel);
                BlockPos placed = origin.add(rel2);
                var previous = occupied.get(placed);
                if (protectSiblingSolids && pb.getTargetState().isAir() && previous != null && !previous.isAir()) continue;
                out.add(new PlannedBlock(placed, pb.getTargetState()));
                if (protectSiblingSolids) occupied.put(placed, pb.getTargetState());
            }
        }
        return out;
    }
}


