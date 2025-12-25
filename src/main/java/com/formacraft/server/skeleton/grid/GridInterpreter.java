package com.formacraft.server.skeleton.grid;

import com.formacraft.common.skeleton.SkeletonPlan;
import com.formacraft.common.skeleton.grid.GridPlan;
import com.formacraft.common.skeleton.transform.BlockTransform;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.skeleton.SkeletonInterpreter;
import com.formacraft.server.skeleton.compound.PlanDispatcher;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * GridInterpreter: repeats a module plan for each placement (translation transform).
 * Delegates module->blocks to a dispatcher for maximum reuse.
 */
public final class GridInterpreter implements SkeletonInterpreter<GridPlan> {
    private final PlanDispatcher dispatcher;

    public GridInterpreter(PlanDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public List<PlannedBlock> interpret(GridPlan plan, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> out = new ArrayList<>(Math.max(2000, plan.placements.size() * 1500));
        SkeletonPlan module = plan.module;
        if (module == null) return out;
        // Interpret module per placement at a shifted origin. This avoids copying relative blocks blindly
        // and keeps child generators free to use origin-based logic.
        for (BlockTransform tx : plan.placements) {
            BlockPos childOrigin = origin.add(tx.dx, tx.dy, tx.dz);
            List<PlannedBlock> childBlocks = dispatcher.interpretChild(module, childOrigin, world);
            if (childBlocks == null || childBlocks.isEmpty()) continue;
            out.addAll(childBlocks);
        }
        return out;
    }
}


