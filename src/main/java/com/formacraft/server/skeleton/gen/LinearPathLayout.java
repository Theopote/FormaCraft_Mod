package com.formacraft.server.skeleton.gen;

import com.formacraft.common.skeleton.ExecutableSkeletonPlan;
import com.formacraft.common.util.FacingUtil;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import java.util.ArrayList;
import java.util.List;

/** Shared geometry for the semantic and legacy LINEAR_PATH routes. */
final class LinearPathLayout {
    record Row(BlockPos center) {}
    record Layout(int width, int start, List<Row> rows) {}

    static Layout create(GenerationContext ctx, ExecutableSkeletonPlan plan, boolean edges) {
        plan.applyParams();
        if (plan.facing == null || plan.facing.getAxis() == Direction.Axis.Y)
            throw new IllegalArgumentException("LINEAR_PATH requires horizontal facing");
        int width = Math.max(1, plan.width), length = Math.max(1, plan.length);
        long count = ((long) width + (edges && width >= 3 ? 2 : 0)) * length;
        if (count > ctx.maxOps) throw new IllegalArgumentException("LINEAR_PATH exceeds operation budget");
        var forward = FacingUtil.forward(plan.facing);
        var rows = new ArrayList<Row>();
        for (int i = 0; i < length; i++) {
            int x = Math.addExact(ctx.origin.getX(), Math.multiplyExact(forward.getX(), i));
            int z = Math.addExact(ctx.origin.getZ(), Math.multiplyExact(forward.getZ(), i));
            long y;
            if (plan.conformTerrain || plan.heightPolicy == ExecutableSkeletonPlan.HeightPolicy.FOLLOW_TERRAIN)
                y = ctx.getSurfaceY(x, z);
            else if (plan.heightPolicy == ExecutableSkeletonPlan.HeightPolicy.STEP_UP)
                y = (long) ctx.origin.getY() + i / 4;
            else if (plan.heightPolicy == ExecutableSkeletonPlan.HeightPolicy.SLOPE)
                y = (long) ctx.origin.getY() + (length == 1 ? 0 : Math.round((double) plan.height * i / (length - 1)));
            else y = ctx.origin.getY();
            long highest = y + (edges && width >= 3 ? 1 : 0);
            if (y < ctx.getBottomY() || highest >= ctx.getTopYExclusive())
                throw new IllegalArgumentException("LINEAR_PATH exceeds world height at row " + i);
            rows.add(new Row(new BlockPos(x, Math.toIntExact(y), z)));
        }
        return new Layout(width, -width / 2, List.copyOf(rows));
    }
}
